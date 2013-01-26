/*
 *  Licensed to Peter Karich under one or more contributor license 
 *  agreements. See the NOTICE file distributed with this work for 
 *  additional information regarding copyright ownership.
 * 
 *  Peter Karich licenses this file to you under the Apache License, 
 *  Version 2.0 (the "License"); you may not use this file except 
 *  in compliance with the License. You may obtain a copy of the 
 *  License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.storage;

import com.graphhopper.coll.MyBitSet;
import com.graphhopper.coll.MyBitSetImpl;
import com.graphhopper.coll.SparseIntIntArray;
import com.graphhopper.routing.util.CarStreetType;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.GraphUtility;
import com.graphhopper.util.Helper;
import com.graphhopper.util.PointList;
import com.graphhopper.util.RawEdgeIterator;
import com.graphhopper.util.shapes.BBox;

/**
 * The main implementation which handles nodes and edges file format. It can be
 * used with different Directory implementations like RAMDirectory for fast and
 * read-thread safe usage which can be flushed to disc or via MMapDirectory for
 * virtual-memory and not thread safe usage.
 *
 * Life cycle: (1) object creation, (2) configuration, (3) createNew or
 * loadExisting, (4) usage, (5) close
 *
 * @see GraphBuilder The GraphBuilder class to easily create a
 * (Level)GraphStorage
 * @see LevelGraphStorage
 * @author Peter Karich
 */
public class GraphStorage implements Graph, Storable {

    // distance of around +-1000 000 meter are ok
    private static final float INT_DIST_FACTOR = 1000f;
    private Directory dir;
    // edge memory layout: nodeA,nodeB,linkA,linkB,dist,flags,geometryRef
    protected final int E_NODEA, E_NODEB, E_LINKA, E_LINKB, E_DIST, E_FLAGS, E_GEO;
    protected int edgeEntrySize;
    protected DataAccess edges;
    /**
     * specified how many entries (integers) are used per edge. starting from 1
     * => fresh int arrays do not need to be initialized with -1
     */
    private int edgeCount = 0;
    // node memory layout: edgeRef,lat,lon
    protected final int N_EDGE_REF, N_LAT, N_LON;
    /**
     * specified how many entries (integers) are used per node
     */
    protected int nodeEntrySize;
    protected DataAccess nodes;
    // starting from 0 (inconsistent :/) => normal iteration and no internal correction is necessary.
    // problem: we exported this to external API => or should we change the edge count in order to 
    // have [0,n) based edge indices in outside API?
    private int nodeCount;
    private BBox bounds;
    // remove markers are not yet persistent!
    private MyBitSet removedNodes;
    private int edgeEntryIndex = -1, nodeEntryIndex = -1;
    // length | nodeA | nextNode | ... | nodeB
    // as we use integer index in 'egdes' area => 'geometry' area is limited to 2GB
    private DataAccess geometry;
    // 0 stands for no separate geoRef
    private int maxGeoRef = 1;
    private boolean initialized = false;

    public GraphStorage(Directory dir) {
        this.dir = dir;
        this.nodes = dir.findCreate("nodes");
        this.edges = dir.findCreate("egdes");
        this.geometry = dir.findCreate("geometry");
        this.bounds = BBox.INVERSE.clone();
        E_NODEA = nextEdgeEntryIndex();
        E_NODEB = nextEdgeEntryIndex();
        E_LINKA = nextEdgeEntryIndex();
        E_LINKB = nextEdgeEntryIndex();
        E_DIST = nextEdgeEntryIndex();
        E_FLAGS = nextEdgeEntryIndex();
        E_GEO = nextEdgeEntryIndex();

        N_EDGE_REF = nextNodeEntryIndex();
        N_LAT = nextNodeEntryIndex();
        N_LON = nextNodeEntryIndex();
        initNodeAndEdgeEntrySize();
    }

    protected final int nextEdgeEntryIndex() {
        edgeEntryIndex++;
        return edgeEntryIndex;
    }

    protected final int nextNodeEntryIndex() {
        nodeEntryIndex++;
        return nodeEntryIndex;
    }

    protected final void initNodeAndEdgeEntrySize() {
        nodeEntrySize = nodeEntryIndex + 1;
        edgeEntrySize = edgeEntryIndex + 1;
    }

    /**
     * @return the directory where this graph is stored.
     */
    public Directory directory() {
        return dir;
    }

    /**
     * After configuring this storage you need to create it explicitly.
     */
    public GraphStorage createNew(int nodeCount) {
        checkAlreadyInitialized();
        int initBytes = Math.max(nodeCount * 4 / 50, 100);
        nodes.createNew((long) 	initBytes * nodeEntrySize);
        initNodeRefs(0, nodes.capacity() / 4);

        edges.createNew((long) initBytes * edgeEntrySize);
        geometry.createNew((long) initBytes);
        initialized = true;
        return this;
    }

    @Override
    public int nodes() {
        return nodeCount;
    }

    @Override
    public double getLatitude(int index) {
        return Helper.intToDegree(nodes.getInt((long) index * nodeEntrySize + N_LAT));
    }

    @Override
    public double getLongitude(int index) {
        return Helper.intToDegree(nodes.getInt((long) index * nodeEntrySize + N_LON));
    }

    /**
     * Translates double VALUE to integer in order to save it in a DataAccess
     * object
     */
    private int distToInt(double f) {
        return (int) (f * INT_DIST_FACTOR);
    }

    /**
     * returns distance (already translated from integer to double)
     */
    private double getDist(long pointer) {
        return (double) edges.getInt(pointer + E_DIST) / INT_DIST_FACTOR;
    }

    @Override
    public void setNode(int index, double lat, double lon) {
        ensureNodeIndex(index);
        long tmp = (long) index * nodeEntrySize;
        nodes.setInt(tmp + N_LAT, Helper.degreeToInt(lat));
        nodes.setInt(tmp + N_LON, Helper.degreeToInt(lon));
        if (lat > bounds.maxLat)
            bounds.maxLat = lat;
        if (lat < bounds.minLat)
            bounds.minLat = lat;
        if (lon > bounds.maxLon)
            bounds.maxLon = lon;
        if (lon < bounds.minLon)
            bounds.minLon = lon;
    }

    private long incCapacity(DataAccess da, long deltaCap) {
        if (!initialized)
            throw new IllegalStateException("Call createNew before or use the GraphBuilder class");
        long newSeg = deltaCap / da.segmentSize();
        if (deltaCap % da.segmentSize() != 0)
            newSeg++;
        long cap = da.capacity() + newSeg * da.segmentSize();
        da.ensureCapacity(cap);
        return cap;
    }

    void ensureNodeIndex(int nodeIndex) {
        if (nodeIndex < nodeCount)
            return;

        long oldNodes = nodeCount;
        nodeCount = nodeIndex + 1;
        long deltaCap = (long) nodeCount * nodeEntrySize * 4 - nodes.capacity();
        if (deltaCap <= 0)
            return;

        long newBytesCapacity = incCapacity(nodes, deltaCap);
        initNodeRefs(oldNodes * nodeEntrySize, newBytesCapacity / 4);
        if (removedNodes != null)
            removedNodes().ensureCapacity((int) (newBytesCapacity / 4 / nodeEntrySize));
    }

    /**
     * Initializes the node area with the empty edge value.
     */
    private void initNodeRefs(long oldCapacity, long newCapacity) {
        for (long pointer = oldCapacity + N_EDGE_REF; pointer < newCapacity; pointer += nodeEntrySize) {
            nodes.setInt(pointer, EdgeIterator.NO_EDGE);
        }
    }

    private void ensureEdgeIndex(int edgeIndex) {
        long deltaCap = (long) edgeIndex * edgeEntrySize * 4 - edges.capacity();
        if (deltaCap <= 0)
            return;

        incCapacity(edges, deltaCap);
    }

    private void ensureGeometry(int index, int size) {
        long deltaCap = ((long) index + size) * 4 - geometry.capacity();
        if (deltaCap <= 0)
            return;

        incCapacity(geometry, deltaCap);
    }

    @Override
    public EdgeIterator edge(int a, int b, double distance, boolean bothDirections) {
        return edge(a, b, distance, CarStreetType.flagsDefault(bothDirections));
    }

    @Override
    public EdgeIterator edge(int a, int b, double distance, int flags) {
        ensureNodeIndex(Math.max(a, b));
        int edge = internalEdgeAdd(a, b, distance, flags);
        EdgeIterable iter = new EdgeIterable(edge, a, false, false);
        iter.next();
        return iter;
    }

    private int nextGeoRef(int arrayLength) {
        int tmp = maxGeoRef;
        // one more integer to store also the size itself
        maxGeoRef += arrayLength + 1;
        return tmp;
    }

    /**
     * @return edgeIdPointer which is edgeId * edgeEntrySize
     */
    int internalEdgeAdd(int fromNodeId, int toNodeId, double dist, int flags) {
        int newOrExistingEdge = nextEdge();
        connectNewEdge(fromNodeId, newOrExistingEdge);
        if (fromNodeId != toNodeId)
            connectNewEdge(toNodeId, newOrExistingEdge);
        writeEdge(newOrExistingEdge, fromNodeId, toNodeId, EdgeIterator.NO_EDGE, EdgeIterator.NO_EDGE, dist, flags);
        return newOrExistingEdge;
    }

    private int nextEdge() {
        int nextEdge = edgeCount;
        edgeCount++;
        if (edgeCount < 0)
            throw new IllegalStateException("too many edges. new edge id would be negative.");
        ensureEdgeIndex(edgeCount);
        return nextEdge;
    }

    private void connectNewEdge(int fromNodeId, int newOrExistingEdge) {
        long nodePointer = (long) fromNodeId * nodeEntrySize;
        int edge = nodes.getInt(nodePointer + N_EDGE_REF);
        if (edge > EdgeIterator.NO_EDGE) {
            // append edge and overwrite EMPTY_LINK
            long lastEdge = getLastEdge(fromNodeId, edge);
            edges.setInt(lastEdge, newOrExistingEdge);
        } else {
            nodes.setInt(nodePointer + N_EDGE_REF, newOrExistingEdge);
        }
    }

    private long writeEdge(int edge, int nodeThis, int nodeOther, int nextEdge, int nextEdgeOther,
            double distance, int flags) {
        if (nodeThis > nodeOther) {
            int tmp = nodeThis;
            nodeThis = nodeOther;
            nodeOther = tmp;

            tmp = nextEdge;
            nextEdge = nextEdgeOther;
            nextEdgeOther = tmp;

            flags = CarStreetType.swapDirection(flags);
        }

        long edgePointer = (long) edge * edgeEntrySize;
        edges.setInt(edgePointer + E_NODEA, nodeThis);
        edges.setInt(edgePointer + E_NODEB, nodeOther);
        edges.setInt(edgePointer + E_LINKA, nextEdge);
        edges.setInt(edgePointer + E_LINKB, nextEdgeOther);
        edges.setInt(edgePointer + E_DIST, distToInt(distance));
        edges.setInt(edgePointer + E_FLAGS, flags);
        return edgePointer;
    }

    protected final long getLinkPosInEdgeArea(int nodeThis, int nodeOther, long edgePointer) {
        return nodeThis <= nodeOther ? edgePointer + E_LINKA : edgePointer + E_LINKB;
    }

    private long getLastEdge(int nodeThis, long edgePointer) {
        long lastLink = -1;
        int i = 0;
        int otherNode = -1;
        for (; i < 1000; i++) {
            edgePointer *= edgeEntrySize;
            otherNode = getOtherNode(nodeThis, edgePointer);
            lastLink = getLinkPosInEdgeArea(nodeThis, otherNode, edgePointer);
            edgePointer = edges.getInt(lastLink);
            if (edgePointer == EdgeIterator.NO_EDGE)
                break;
        }

        if (i >= 1000)
            throw new IllegalStateException("endless loop? edge count of " + nodeThis
                    + " is probably not higher than " + i
                    + ", edgePointer:" + edgePointer + ", otherNode:" + otherNode);
        return lastLink;
    }

    private int getOtherNode(int nodeThis, long edgePointer) {
        int nodeA = edges.getInt(edgePointer + E_NODEA);
        if (nodeA == nodeThis)
            // return b
            return edges.getInt(edgePointer + E_NODEB);
        // return a
        return nodeA;
    }

    @Override
    public RawEdgeIterator allEdges() {
        return new AllEdgeIterator();
    }

    /**
     * Include all edges of this storage in the iterator.
     */
    protected class AllEdgeIterator implements RawEdgeIterator {

        protected long edgePointer = -edgeEntrySize;
        private int maxEdges = edgeCount * edgeEntrySize;

        @Override public boolean next() {
            edgePointer += edgeEntrySize;
            return edgePointer < maxEdges;
        }

        @Override public int nodeA() {
            return edges.getInt(edgePointer + E_NODEA);
        }

        @Override public int nodeB() {
            return edges.getInt(edgePointer + E_NODEB);
        }

        @Override public double distance() {
            return getDist(edgePointer);
        }

        @Override public void distance(double dist) {
            edges.setInt(edgePointer + E_DIST, distToInt(dist));
        }

        @Override public int flags() {
            return edges.getInt(edgePointer + E_FLAGS);
        }

        @Override public void flags(int flags) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override public int edge() {
            return (int) (edgePointer / edgeEntrySize);
        }

        @Override public boolean isEmpty() {
            return false;
        }
    }

    @Override
    public EdgeIterator getEdgeProps(int edgeId, final int endNode) {
        if (edgeId <= EdgeIterator.NO_EDGE || edgeId > edgeCount)
            throw new IllegalStateException("edgeId " + edgeId + " out of bounds [0," + edgeCount + "]");
        if (endNode < 0)
            throw new IllegalStateException("endNode " + endNode + " out of bounds [0," + nodeCount + "]");
        long edgePointer = (long) edgeId * edgeEntrySize;
        // a bit complex but faster
        int nodeA = edges.getInt(edgePointer + E_NODEA);
        int nodeB = edges.getInt(edgePointer + E_NODEB);
        SingleEdge edge;
        if (endNode == nodeB) {
            edge = createSingleEdge(edgeId, nodeA);
            edge.node = nodeB;
            return edge;
        } else if (endNode == nodeA) {
            edge = createSingleEdge(edgeId, nodeB);
            edge.node = nodeA;
            edge.switchFlags = true;
            return edge;
        } else
            return GraphUtility.EMPTY;
    }

    protected SingleEdge createSingleEdge(int edgeId, int nodeId) {
        return new SingleEdge(edgeId, nodeId);
    }

    protected class SingleEdge extends EdgeIterable {

        protected boolean switchFlags;

        public SingleEdge(int edgeId, int nodeId) {
            super(edgeId, nodeId, false, false);
            edgePointer = edgeId * edgeEntrySize;
            flags = flags();
        }

        @Override public boolean next() {
            return false;
        }

        @Override public int flags() {
            flags = edges.getInt(edgePointer + E_FLAGS);
            if (switchFlags)
                return CarStreetType.swapDirection(flags);
            return flags;
        }
    }

    @Override
    public EdgeIterator getEdges(int node) {
        return createEdgeIterable(node, true, true);
    }

    @Override
    public EdgeIterator getIncoming(int node) {
        return createEdgeIterable(node, true, false);
    }

    @Override
    public EdgeIterator getOutgoing(int node) {
        return createEdgeIterable(node, false, true);
    }

    protected EdgeIterator createEdgeIterable(int baseNode, boolean in, boolean out) {
        int edge = nodes.getInt((long) baseNode * nodeEntrySize + N_EDGE_REF);
        return new EdgeIterable(edge, baseNode, in, out);
    }

    protected class EdgeIterable implements EdgeIterator {

        long edgePointer;
        boolean in;
        boolean out;
        // edge properties
        int flags;
        int node;
        final int baseNode;
        int edgeId;
        int nextEdge;

        // used for SingleEdge and as return value of edge()
        public EdgeIterable(int edge, int baseNode, boolean in, boolean out) {
            this.nextEdge = this.edgeId = edge;
            this.edgePointer = (long) nextEdge * edgeEntrySize;
            this.baseNode = baseNode;
            this.in = in;
            this.out = out;
        }

        boolean readNext() {
            edgePointer = (long) nextEdge * edgeEntrySize;
            edgeId = nextEdge;
            node = getOtherNode(baseNode, edgePointer);

            // position to next edge
            nextEdge = edges.getInt(getLinkPosInEdgeArea(baseNode, node, edgePointer));
            if (nextEdge == edgeId)
                throw new AssertionError("endless loop detected for " + baseNode + "," + node + "," + edgePointer);

            flags = edges.getInt(edgePointer + E_FLAGS);

            // switch direction flags if necessary
            if (baseNode > node)
                flags = CarStreetType.swapDirection(flags);

            if (!in && !CarStreetType.isForward(flags) || !out && !CarStreetType.isBackward(flags)) {
                // skip this edge as it does not fit to defined filter
                return false;
            } else {
                return true;
            }
        }

        @Override public boolean next() {
            int i = 0;
            boolean foundNext = false;
            for (; i < 1000; i++) {
                if (nextEdge == EdgeIterator.NO_EDGE)
                    break;
                foundNext = readNext();
                if (foundNext)
                    break;
            }
            // road networks typically do not have nodes with plenty of edges!
            if (i > 1000)
                throw new IllegalStateException("something went wrong: no end of edge-list found");
            return foundNext;
        }

        @Override public int node() {
            return node;
        }

        @Override public double distance() {
            return getDist(edgePointer);
        }

        @Override public void distance(double dist) {
            edges.setInt(edgePointer + E_DIST, distToInt(dist));
        }

        @Override public int flags() {
            return flags;
        }

        @Override public void flags(int fl) {
            flags = fl;
            int nep = edges.getInt(getLinkPosInEdgeArea(baseNode, node, edgePointer));
            int neop = edges.getInt(getLinkPosInEdgeArea(node, baseNode, edgePointer));
            writeEdge(edge(), baseNode, node, nep, neop, distance(), flags);
        }

        @Override public int baseNode() {
            return baseNode;
        }

        @Override public void wayGeometry(PointList pillarNodes) {
            if (pillarNodes != null && !pillarNodes.isEmpty()) {
                int len = pillarNodes.size();
                int geoRef = nextGeoRef(len * 2);
                edges.setInt(edgePointer + E_GEO, geoRef);
                ensureGeometry(geoRef, len * 2 + 1);
                geometry.setInt(geoRef, len);
                geoRef++;
                if (baseNode > node)
                    pillarNodes.reverse();

                for (int i = 0; i < len; geoRef += 2, i++) {
                    geometry.setInt(geoRef, Helper.degreeToInt(pillarNodes.latitude(i)));
                    geometry.setInt(geoRef + 1, Helper.degreeToInt(pillarNodes.longitude(i)));
                }
            } else
                edges.setInt(edgePointer + E_GEO, EdgeIterator.NO_EDGE);
        }

        @Override public PointList wayGeometry() {
            int geoRef = edges.getInt(edgePointer + E_GEO);
            int count = 0;
            if (geoRef > EdgeIterator.NO_EDGE)
                count = geometry.getInt(geoRef);
            PointList pillarNodes = new PointList(count);
            for (int i = 0; i < count; i++) {
                double lat = Helper.intToDegree(geometry.getInt(geoRef + i * 2 + 1));
                double lon = Helper.intToDegree(geometry.getInt(geoRef + i * 2 + 2));
                pillarNodes.add(lat, lon);
            }
            if (baseNode > node)
                pillarNodes.reverse();
            return pillarNodes;
        }

        @Override public int edge() {
            return edgeId;
        }

        @Override public boolean isEmpty() {
            return false;
        }
    }

    private MyBitSet removedNodes() {
        if (removedNodes == null)
            removedNodes = new MyBitSetImpl((int) (nodes.capacity() / 4));
        return removedNodes;
    }

    private void checkAlreadyInitialized() {
        if (initialized)
            throw new IllegalStateException("Already initialized GraphStorage.");
    }

    @Override
    public boolean loadExisting() {
        checkAlreadyInitialized();
        if (edges.loadExisting()) {
            if (!nodes.loadExisting())
                throw new IllegalStateException("cannot load nodes. corrupt file or directory? " + dir);
            if (!geometry.loadExisting())
                throw new IllegalStateException("cannot load geometry. corrupt file or directory? " + dir);
            if (nodes.version() != edges.version())
                throw new IllegalStateException("nodes and edges files have different versions!? " + dir);
            // nodes
            int hash = nodes.getHeader(0);
            if (hash != getClass().getName().hashCode())
                throw new IllegalStateException("Cannot load the graph - it wasn't create via "
                        + getClass().getName() + "! " + dir);

            nodeEntrySize = nodes.getHeader(1);
            nodeCount = nodes.getHeader(2);
            bounds.minLon = Helper.intToDegree(nodes.getHeader(3));
            bounds.maxLon = Helper.intToDegree(nodes.getHeader(4));
            bounds.minLat = Helper.intToDegree(nodes.getHeader(5));
            bounds.maxLat = Helper.intToDegree(nodes.getHeader(6));

            // edges
            edgeEntrySize = edges.getHeader(0);
            edgeCount = edges.getHeader(1);

            // geometry
            maxGeoRef = edges.getHeader(0);
            initialized = true;
            return true;
        }
        return false;
    }

    @Override
    public void flush() {
        // nodes
        nodes.setHeader(0, getClass().getName().hashCode());
        nodes.setHeader(1, nodeEntrySize);
        nodes.setHeader(2, nodeCount);
        nodes.setHeader(3, Helper.degreeToInt(bounds.minLon));
        nodes.setHeader(4, Helper.degreeToInt(bounds.maxLon));
        nodes.setHeader(5, Helper.degreeToInt(bounds.minLat));
        nodes.setHeader(6, Helper.degreeToInt(bounds.maxLat));

        // edges
        edges.setHeader(0, edgeEntrySize);
        edges.setHeader(1, edgeCount);

        // geometry
        geometry.setHeader(0, maxGeoRef);

        geometry.flush();
        edges.flush();
        nodes.flush();
    }

    @Override
    public void close() {
        edges.close();
        nodes.close();
    }

    @Override
    public long capacity() {
        return edges.capacity() + nodes.capacity();
    }

    public int version() {
        return nodes.version();
    }

    @Override public String toString() {
        return "edges:" + edgeCount + "(" + edges.capacity() / Helper.MB + "), "
                + "nodes:" + nodeCount + "(" + nodes.capacity() / Helper.MB + "), "
                + "geo:" + maxGeoRef + "(" + geometry.capacity() / Helper.MB + "), "
                + "bounds:" + bounds;
    }
}
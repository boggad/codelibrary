import java.util.Arrays;

public class SegmentsUnion {

	public static double segmentsUnionLength(double[] x1, double[] x2) {
		int n = x1.length;
		Point[] points = new Point[n * 2];
		for (int i = 0; i < n; i++) {
			points[i * 2] = new Point(x1[i], 0);
			points[i * 2 + 1] = new Point(x2[i], 1);
		}
		Arrays.sort(points);
		double union = 0;
		int balance = 0;
		for (int i = 0; i < points.length; i++) {
			if (balance > 0 && i > 0)
				union += points[i].x - points[i - 1].x;
			if (points[i].type == 0)
				++balance;
			else
				--balance;
		}
		return union;
	}

	static class Point implements Comparable<Point> {
		double x;
		int type;

		Point(double x, int type) {
			this.x = x;
			this.type = type;
		}

		@Override
		public int compareTo(Point o) {
			double eps = 1e-7;
			return Math.abs(x - o.x) > eps ? Double.compare(x, o.x) : Integer.compare(type, o.type);
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == null || !(obj instanceof Point)) {
				return false;
			}
			Point p = (Point) obj;
			double eps = 1e-7;
			if (Math.abs(x - p.x) <= eps) {
				return Integer.compare(type, p.type) == 0;
			}
			return Double.compare(x, p.x) == 0;
		}

		@Override
		public int hashCode() {
			int hash = 17;
			long xBits = Double.doubleToLongBits(x);
			int xHash = (int) (xBits ^ (xBits >>> 32));
			hash = (hash << 5) - hash + xHash;
			hash = (hash << 5) - hash + type;
			return hash;
		}
	}

	// Usage example
	public static void main(String[] args) {
		System.out.println(segmentsUnionLength(new double[]{5, 10}, new double[]{15, 20}));
	}
}

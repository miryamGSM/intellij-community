class T {
  static class P { String n; }

  static boolean notSame(P a, P b) {
    return a.n != b.n && (a.n == null || !a.n.<caret>equals(b.n));
  }
}
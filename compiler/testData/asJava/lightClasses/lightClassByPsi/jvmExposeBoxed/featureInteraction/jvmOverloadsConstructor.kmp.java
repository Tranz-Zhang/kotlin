public final class Baz /* Baz*/ {
  @<error>()
  @<error>()
  @kotlin.OptIn(markerClass = {kotlin.ExperimentalStdlibApi.class})
  private  Baz(int, int);//  .ctor(int, int)
}

@<error>()
public final class IntWrapper /* IntWrapper*/ {
  private final int s;

  @org.jetbrains.annotations.NotNull()
  public @org.jetbrains.annotations.NotNull() java.lang.String toString();//  toString()

  public boolean equals(@org.jetbrains.annotations.Nullable() @org.jetbrains.annotations.Nullable() java.lang.Object);//  equals(@org.jetbrains.annotations.Nullable() java.lang.Object)

  public final int getS();//  getS()

  public int hashCode();//  hashCode()
}

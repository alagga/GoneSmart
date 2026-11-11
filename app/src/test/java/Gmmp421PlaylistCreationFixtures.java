import java.util.ArrayList;
import java.util.List;

// Test-only miniature of the 4.2.1 APK-verified native create field chain.
class sa4 {
    public Object getValue() { return "/storage/emulated/0/gmmp/playlists"; }
}

class r15 {
    public Object getValue() { return new sa4(); }
}

class vp3 {
    final r15 E = new r15();
}

class tp3 {
    final vp3 w = new vp3();
}

class io3 {
    List<?> a = new ArrayList<>();
}

class ho3 extends tp3 {
    final io3 z = new io3();
}

class sp3 {
    final tp3 n = new tp3();
    sp3(tp3 p) { }
    public Object invoke(Object first, Object second) { return ag5.a; }
}

class go3 {
    final ho3 n = new ho3();
    go3(ho3 p) { }
    public Object invoke(Object first, Object second) { return ag5.a; }
}

class ag5 {
    static final ag5 a = new ag5();
}

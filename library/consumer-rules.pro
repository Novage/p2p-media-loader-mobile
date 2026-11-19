# Rules for the apps that shrink the library with R8.

# native/src/jni.c: the JNI function names follow these names, and JNI_OnLoad
# finds the exceptions' classes and constructors, and the sinks' methods,
# by name.
-keep class com.novage.p2pml.engine.NativeEngine {
    native <methods>;
}
-keep class com.novage.p2pml.engine.JsException {
    <init>(java.lang.String, java.lang.String);
}
-keep class com.novage.p2pml.engine.JsEngineClosedException {
    <init>(java.lang.String);
}
-keep interface com.novage.p2pml.engine.NativeEngine$LogSink {
    void write(int, java.lang.String);
}
-keep interface com.novage.p2pml.engine.NativeEngine$HttpSink {
    void start(int, java.lang.String, java.lang.String, java.lang.String[]);
    void cancel(int);
}
-keep interface com.novage.p2pml.engine.NativeEngine$WsSink {
    void open(int, java.lang.String, java.lang.String[]);
    void send(int, boolean, byte[]);
    void close(int, int, byte[]);
    void cancel(int);
}
-keep interface com.novage.p2pml.engine.NativeEngine$PlayerSink {
    void miss(int);
    void segment(int, byte[]);
    void fail(int, byte[]);
}

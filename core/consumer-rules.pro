# Rules for the apps that shrink the library with R8.

# native/src/jni.c: the JNI function names follow these names, and JNI_OnLoad
# finds the exceptions' classes and constructors, and the sinks' methods,
# by name. Every method of a sink is called from C, so a rule keeps them all,
# whatever their parameters.
-keep class com.novage.p2pml.engine.NativeEngine {
    native <methods>;
}
-keep class com.novage.p2pml.engine.JsException {
    <init>(java.lang.String, java.lang.String);
}
-keep class com.novage.p2pml.engine.JsEngineClosedException {
    <init>(java.lang.String);
}
-keep interface com.novage.p2pml.engine.NativeEngine$LogSink { <methods>; }
-keep interface com.novage.p2pml.engine.NativeEngine$HttpSink { <methods>; }
-keep interface com.novage.p2pml.engine.NativeEngine$WsSink { <methods>; }
-keep interface com.novage.p2pml.engine.NativeEngine$PlayerSink { <methods>; }

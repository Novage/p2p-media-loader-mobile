# Rules for the apps that shrink the library with R8.

# native/src/jni.c: the JNI function names follow these names, and JNI_OnLoad
# finds the exceptions' classes and constructors by name.
-keep class com.novage.p2pml.engine.NativeEngine {
    native <methods>;
}
-keep class com.novage.p2pml.engine.JsException {
    <init>(java.lang.String, java.lang.String);
}
-keep class com.novage.p2pml.engine.JsEngineClosedException {
    <init>(java.lang.String);
}

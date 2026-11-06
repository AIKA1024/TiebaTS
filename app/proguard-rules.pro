-allowaccessmodification
-overloadaggressively

# Modern Xposed module entry. R8 rewrites java_init.list if the class gets obfuscated.
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class gm.tieba.tabswitch.XposedInit { public <init>(); }

-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void check*(...);
	public static void throw*(...);
}

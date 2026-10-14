# Consumer R8/ProGuard rules packaged into mirutime-core-android.aar
# (becomes proguard.txt inside the AAR; a consuming app's R8 picks them up
# automatically — nothing to wire in the app).
#
# Policy: MINIMAL and justified. Every block must say WHY it exists and what
# its ceiling is. Do not add "just in case" rules.

# --- LuaJ (org.luaj:luaj-jse 3.0.1) ----------------------------------------
# WHY: LuaJ's JSE interop resolves Java classes and members BY NAME at
# runtime: org.luaj.vm2.lib.jse.{LuajavaLib, JavaClass, JavaMethod,
# JavaConstructor, JavaInstance, JavaArray, JavaArray$LenFunction,
# CoerceJavaToLua, CoerceLuaToJava, LuajavaLib$ProxyInvocationHandler} call
# java.lang.reflect.{Method,Constructor,Field,Proxy} and Class.forName. The
# lib is ECJ-compiled, so plain class literals go through the
# `class$xxx = Class.forName("...")` idiom as well.
# JsePlatform.standardGlobals() registers LuajavaLib into every sandbox (our
# Sandbox.kt then nils the `luajava` global, but the classes stay reachable
# on the class path), and Lua-side code addresses those globals by name.
# luaj-jse ships no rules of its own (no META-INF/proguard entry), so under
# R8 full mode the reflective classes may be renamed/merged/stripped without
# any build error — the breakage would appear only at plugin runtime.
# CEILING: the whole package is kept — luaj-jse is 347 KB / 344 classes and
# nothing under org.luaj shrinks or gets obfuscated; org.luaj.vm2.{luajc,
# server, script} is never loaded by us. If APK size ever matters, narrow to
# org.luaj.vm2.{lib,compiler,parser,ast}** and re-run a minified consumer
# build before trusting it.
-keep class org.luaj.** { *; }

# --- snakeyaml (org.yaml:snakeyaml 2.7) -------------------------------------
# WHY: the only call site is `IndexParser` — `Yaml().load<Any>(yamlText)` on
# index.yaml — but snakeyaml produces values through reflection:
# BaseConstructor.newInstance -> Class.getDeclaredConstructor +
# Constructor.newInstance (setAccessible), property discovery in
# org.yaml.snakeyaml.introspector.* (MethodProperty/FieldProperty/
# GenericProperty/PropertyUtils) and TypeDescription. snakeyaml ships no
# consumer rules either, and from a single call site R8 may classify the
# bean/introspection path as dead code and remove it — that failure would
# show up at runtime on the next index format, not at build time.
# CEILING: the whole package is kept — snakeyaml is 332 KB / 230 classes and
# nothing under org.yaml.snakeyaml shrinks. Narrowing to
# org.yaml.snakeyaml.{constructor,introspector,representer}** is possible but
# has to be re-validated on every snakeyaml upgrade.
-keep class org.yaml.snakeyaml.** { *; }

# --- kotlinx-serialization: nothing added, on purpose ----------------------
# Checked: mirutime-core defines no reflective/polymorphic serializers of its
# own — no SerializersModule, no @Polymorphic, no hand-written KSerializer;
# every serializer is @Serializable-generated and reached statically through
# reified serializer<T>(). The runtime already ships its consumer rules in
# META-INF/proguard/kotlinx-serialization-common.pro (Companion / INSTANCE
# .serializer() keeps, -keepattributes RuntimeVisibleAnnotations, …), which
# R8 merges from the dependency jars. Anything here would be a duplicate.

# --- Ktor / OkHttp / kotlinx-coroutines / kotlinx-serialization / ksoup / okio
# No rules added, deliberately: ktor-utils (META-INF/proguard/ktor.pro),
# okhttp (okhttp3.pro), kotlinx-coroutines-core (coroutines.pro) and
# kotlinx-serialization-core (kotlinx-serialization-common.pro) ship their own
# consumer rules, verified in the resolved jars — copying them here only
# creates drift. ksoup 0.2.6 and okio 3.18.2 ship none but need none: no
# reflective entry point is used (okio's only reflective code is its own
# Java-serialization readObject, which this library never calls).

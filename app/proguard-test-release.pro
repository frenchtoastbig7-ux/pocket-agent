# Only applied with -PtestRelease. The instrumentation APK resolves shared classes (the test runner's tracing
# dependency, the Kotlin standard library, and the app classes the tests call) from the app under test, so R8 must
# not strip them. Production rules in proguard-rules.pro still govern Ktor, the MCP SDK and the Anthropic SDK.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class dev.pocketagent.** { *; }

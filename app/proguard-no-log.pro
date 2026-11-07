# The release build writes no state, timing or count to the log (keething-spec security.storage AC1): Log.v, Log.d and
# Log.i are removed with the strings they build. Log.w and Log.e stay; they carry a failure's class name, a count or a
# fixed text, never what a database holds. Left out only by a measurement build (-Pkeething.timings, app/build.gradle.kts),
# which tools/build-apk.sh never ships.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# HttpClient() discovers its JVM engine through ServiceLoader. ProGuard does
# not treat META-INF/services entries as bytecode references, so keep the CIO
# provider and everything it exposes to the loader.
-keep class io.ktor.client.engine.cio.CIOEngineContainer {
    *;
}

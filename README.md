## Proxy Witness
This minimalistic (and probably not spec compliant) HTTP/1.1 proxy will verify the checksum of all files downloaded through it.
Its main purpose is to verify downloaded maven artifacts in a (somewhat) build-system agnostic way.

### Installation
proxy-witness is built from source using the Gradle build-system.
The included wrapper will automatically download and verify the Gradle runtime.
```bash
git clone https://github.com/johni0702/proxy-witness
cd proxy-witness
./gradlew build
```
The compiled jar file will be at `build/libs/proxy-witness.jar`.

### Usage
```bash
java -jar proxy-witness.jar <port> <checksums>
```
where `checksums` is a file with lines in the form of `<hash> <urlSuffix>` where `hash` is the SHA-256 hash of the file whose URL ends in `urlSuffix`.

When the proxy is run with an empty `checksums` file, it will not check any hashes but instead print their values to stdout.
This allows you to use the proxy in a trust-on-first-use mode by running:
```bash
java -jar proxy-witness.jar <port> emptyfile.txt > checksums.txt
```

Note that this proxy will refuse incoming https requests because it will not be able to inspect their traffic.
Instead the build system should be configured to use http for all repositories. The proxy will then convert all incoming http requests to outgoing https requests.
If some site does not support https, all URLs of that site need to be manually whitelisted on startup:
```bash
java -Dproxywitness.httpUris="http://example.com/somefile,http://example2.com/some/other/file" -jar proxy-witness.jar ...
```

It is also possible to not check the hash for specific paths by setting their hash in the `checksums` file to `*`.
Note that this might allow an attacker to circumvent the proxy by getting the build-system to download a different file from an URL with the same suffix.
As such, this should be used with caution and will probably be changed to check the full URL instead of only a suffix in the future.

#### Configuring Gradle
The simplest way to automatically convert all maven repos to http is to use an [init.gradle](https://docs.gradle.org/current/userguide/init_scripts.html) script:
```groovy
def convertRepoToHttp = { repo ->
    if (repo instanceof MavenArtifactRepository && repo.url.toString().startsWith('https://')) {
        URL url = repo.url.toURL()
        repo.url = new URL("http", url.getHost(), url.getPort(), url.getFile()).toURI()
    }
}
allprojects {
    buildscript {
       repositories.all convertRepoToHttp
    }
    repositories.all convertRepoToHttp
}
```
You can then run your unmodified build by passing some additional options to Gradle (or the wrapper):
```bash
./gradlew -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=$PROXY_PORT -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=$PROXY_PORT -I init.gradle build
```
where `$PROXY_PORT` is the port that proxy-witness is running on.

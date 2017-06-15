# tcp2igz
A small 
app for accepting CSV over TCP and sending it to Iguazio's KV store over HTTP.

# Requirements
Java 8

# Build
`sbt assembly` will create `target/scala-2.12/tcp2igz-assembly-0.1.jar`.

# Run
`java -jar tcp2igz-assembly-0.1.jar` will run the app (no command line arguments required).

# Configuration
See [application.conf](src/main/resources/application.conf). To override a configuration, use this form:

`java -Dtarget.host=localhost -jar tcp2igz-assembly-0.1.jar`

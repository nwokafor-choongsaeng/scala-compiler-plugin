# scala-compiler-plugin

Dependencies required to start building a compiler plugin for scala 2.13.

- org.scala-lang:scala-compiler:2.13
- org.scala-lang:scala-reflect:2.13

### Using 3rd party libraries
Using dependencies (3rd party libraries from a remote repo) in your compiler plugin is tricky.
The reason this is complicated is that the dependencies are not included as part of your plugin.
You have the reponsiblity of ensuring that the dependencies are available on the classpath where your
plugin lives before it runs.

The way I solved this now is to use a fat jar. With a fat jar, the dependencies are included as part of your plugin.


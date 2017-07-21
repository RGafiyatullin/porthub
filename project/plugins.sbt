logLevel := Level.Warn

resolvers += Classpaths.typesafeReleases

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.0.6")

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.4.6")

addSbtPlugin("com.typesafe.sbt" % "sbt-twirl" % "1.1.1")

//addSbtPlugin("org.scala-sbt.plugins" % "sbt-onejar" % "0.8")

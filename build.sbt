
name := "squeryl"

organization := "org.squeryl"

version := "0.9.5-rc1-proxy"

version <<= version { v => 
  val snapshot = Option(System.getProperty("snapshot")) == Some("true")
  if(snapshot)
    v + "-SNAPSHOT"
  else
    v
}

scalaVersion := "2.9.1"

crossScalaVersions := Seq("2.9.1","2.9.0-1","2.9.0","2.8.1","2.8.0")

libraryDependencies ++= Seq(
  "cglib" % "cglib-nodep" % "2.2",
  "com.h2database" % "h2" % "1.2.127" % "provided",
  "mysql" % "mysql-connector-java" % "5.1.10" % "provided",
  "postgresql" % "postgresql" % "8.4-701.jdbc4" % "provided",
  "net.sourceforge.jtds" % "jtds" % "1.2.4" % "provided",
  "org.apache.derby" % "derby" % "10.7.1.1" % "provided",
  "junit" % "junit" % "4.8.2" % "provided"
)

libraryDependencies <+= scalaVersion("org.scala-lang" % "scalap" % _ % "provided")
  

libraryDependencies <+= scalaVersion(sv => sv match {
     case _ if sv startsWith "2.9" => 
	   "org.scalatest" % "scalatest_2.9.1" % "1.6.1" % "test"
     case _ =>
	   "org.scalatest" % "scalatest_2.8.2" % "1.5.1" % "test"
  })

retrieveManaged := true  

parallelExecution := false

publishMavenStyle := true

publishTo <<= (version) { version: String =>
  val repo = "http://192.168.0.7:8080/archiva/repository/"
  if (version.trim.endsWith("SNAPSHOT")) 
    Some("Repository Archiva Managed snapshots Repository" at repo + "snapshots/")
  else
    Some("Repository Archiva Managed internal Repository" at repo + "internal/")
}

credentials += Credentials("Repository Archiva Managed internal Repository", "192.168.0.7", "xxxx", "xxxx")

pomExtra :=
    <licenses>
      <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
      <distribution>repo</distribution>
      </license>
    </licenses>

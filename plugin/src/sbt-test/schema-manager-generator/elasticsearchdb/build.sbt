enablePlugins(DbevolvPlugin)

import java.net.ServerSocket
import org.apache.commons.io.FileUtils
import java.security.MessageDigest
import com.mnubo._
import com.mnubo.docker_utils.docker.Docker._
import org.elasticsearch.action.admin.cluster.health.ClusterHealthStatus
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.settings.ImmutableSettings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.search.SearchHit

import scala.annotation.tailrec
import scala.util.Try
import scala.util.control.NonFatal
import sys.process.{Process => SProcess, ProcessLogger => SProcessLogger}
import collection.JavaConverters._

resolvers += "Mnubo release repository" at "http://artifactory.mtl.mnubo.com:8081/artifactory/libs-release-local/" // Temporary while removing all of our deps

TaskKey[Unit]("check-mgr") := {
  val logger = streams.value.log

  def runShellAndListen(cmd: String) = {
    val out = new StringBuilder
    val err = new StringBuilder
    val l = SProcessLogger(o => out.append(o + "\n"), e => err.append(e) + "\n")

    logger.info(cmd)
    SProcess(cmd) ! l
    out.toString.trim + err.toString.trim
  }

  def runShell(cmd: String) = {
    logger.info(cmd)
    SProcess(cmd) !
  }

  val indexName = "elasticsearchdb"
  val typeName = "kv"

  case class Elasticsearch() {
    val port = using(new ServerSocket(0))(_.getLocalPort)

    runShell("docker pull elasticsearch:1.5")

    val elasticsearchContainerId =
      runShellAndListen(s"docker run -d -p $port:9300 elasticsearch:1.5")

    logger.info(s"ES container id: $elasticsearchContainerId")

    private def isStarted = {
      try {
        runShellAndListen(s"docker logs $elasticsearchContainerId").contains("] indices into cluster_state") &&
        using(new TransportClient(ImmutableSettings.builder().classLoader(getClass.getClassLoader).build()).addTransportAddresses(new InetSocketTransportAddress(host, port))) { tempClient =>
          val health = tempClient
            .admin()
            .cluster()
            .prepareHealth()
            .get

          health.getStatus == ClusterHealthStatus.GREEN
        }
      }
      catch {
        case NonFatal(ex) =>
          logger.trace(ex)
          false
      }
    }

    @tailrec
    private def waitStarted: Unit =
      if (!isStarted) {
        Thread.sleep(500)
        waitStarted
      }

    waitStarted

    val client = new TransportClient(ImmutableSettings.builder().classLoader(getClass.getClassLoader).build()).addTransportAddresses(new InetSocketTransportAddress(host, port))

    def count =
      client
        .prepareCount(indexName)
        .setTypes(typeName)
        .setQuery(QueryBuilders.matchAllQuery())
        .get
        .getCount

    def metadata =
      client
        .prepareSearch(indexName)
        .setTypes("elasticsearchdb_version")
        .setQuery(QueryBuilders.matchAllQuery())
        .setSize(10000)
        .execute()
        .actionGet()
        .getHits
        .getHits
        .map(new Metadata(_))
        .map { m => println(m); m}
        .sortBy(_.version)
        .toSeq

    def close(): Unit = {
      client.close()
      s"docker stop $elasticsearchContainerId".!
      s"docker rm -v $elasticsearchContainerId".!
    }
  }

  case class Metadata(version: String, checksum: String) {
    def this(hit: SearchHit) = this(hit.getId, hit.getSource.get("checksum").asInstanceOf[String])
  }

  val dockerExec =
    runShellAndListen("which docker")
  val userHome =
    System.getenv("HOME")

  using(Elasticsearch()) { es =>
    import es._

    val mgrCmd =
      s"docker run -i --rm --link $elasticsearchContainerId:elasticsearch -v $userHome/.dockercfg:/root/.dockercfg -v /var/run/docker.sock:/run/docker.sock -v $dockerExec:/bin/docker -v $userHome/.docker/config.json:/root/.docker/config.json:ro -e ENV=integration elasticsearchdb-mgr:1.0.0-SNAPSHOT"

    // Run the schema manager to migrate the db to latest version
    assert(
      runShell(mgrCmd) == 0,
      "The schema manager failed."
    )

    assert(
      count == 2L,
      "Could not query the created table"
    )

    logger.info("Pwd: " + new File(".").getCanonicalPath)
    val bytesScala1 = FileUtils.readFileToByteArray(new File("src/main/scala/elasticsearchdb/ScalaUp0001.scala"))
    val bytesEs1 = FileUtils.readFileToByteArray(new File("migrations/0001/upgrade.es"))
    val checksum1 = MessageDigest
      .getInstance("MD5")
      .digest(bytesScala1 ++ bytesEs1)
      .map("%02x".format(_))
      .mkString

    val bytesJava2 = FileUtils.readFileToByteArray(new File("src/main/java/elasticsearchdb/JavaUp0002.java"))
    val bytesScala2 = FileUtils.readFileToByteArray(new File("src/main/scala/elasticsearchdb/ScalaUp0002.scala"))
    val bytesEs2 = FileUtils.readFileToByteArray(new File("migrations/0002/upgrade.es"))
    val checksum2 = MessageDigest
      .getInstance("MD5")
      .digest(bytesJava2 ++ bytesScala2 ++ bytesEs2)
      .map("%02x".format(_))
      .mkString

    val expectedMetadata = Seq(
      Metadata("0001", checksum1),
      Metadata("0002", checksum2)
    )

    assert(
      metadata == expectedMetadata,
      s"Actual metadata ($metadata) do not match expected ($expectedMetadata)"
    )

    // Run the schema manager to display history
    val history =
      runShellAndListen(s"$mgrCmd --history")
    logger.info(history)
    val historyRegex =
      ("""History of elasticsearchdb:\s+Version\s+Date\s+Checksum\s+0001\s+\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d.\d\d\dZ\s+""" + checksum1 + """\s+0002\s+\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d.\d\d\dZ\s+""" + checksum2).r
    assert(
       historyRegex.findFirstIn(history).isDefined,
      "The schema manager did not report history properly."
    )

    // Run the schema manager to downgrade to previous version
    runShell(s"$mgrCmd --version 0001")

    assert(
      count == 0,
      "Downgrade did not bring back the schema to the expected state"
    )

    assert(
      metadata == Seq(Metadata("0001", checksum1)),
      "Metadata is not updated correctly after a downgrade"
    )


    // Fiddle with checksum and make sure the schema manager refuses to proceed
    client
      .prepareUpdate(indexName, "elasticsearchdb_version", "0001")
      .setDoc("checksum", "abc")
      .get()

    assert(
      runShell(mgrCmd) != 0,
      "The schema manager should not have accepted to proceed with a wrong checksum"
    )

    client
      .prepareUpdate(indexName, "elasticsearchdb_version", "0001")
      .setDoc("checksum", checksum1)
      .get()

    // Fiddle with schema and make sure the schema manager refuses to proceed
    client
      .admin
      .indices
      .prepareDeleteMapping(indexName)
      .setType("kv")
      .get

    assert(
      runShell(mgrCmd) != 0,
      "The schema manager should not have accepted to proceed with a wrong schema"
    )

    client
      .admin
      .indices
      .preparePutMapping(indexName)
      .setType("kv")
      .setSource(
        """
          |{
          |  "kv": {
          |    "properties": {
          |      "k": {"type": "string", "index": "not_analyzed"},
          |      "v": {"type": "string", "index": "not_analyzed"},
          |      "meta": {
          |        "type": "nested",
          |        "properties": {
          |          "category": {"type": "string", "index": "not_analyzed"}
          |        }
          |      }
          |    }
          |  }
          |}
        """.stripMargin)
      .get

    // Finally, make sure we can re-apply latest migration
    assert(
      runShell(mgrCmd) == 0,
      "The schema manager should have run successfully"
    )

  }

  s"docker rmi -f elasticsearchdb-mgr:1.0.0-SNAPSHOT".!
  s"docker rmi -f elasticsearchdb-mgr:latest".!
  s"docker rmi -f test-elasticsearchdb:0002".!
  s"docker rmi -f test-elasticsearchdb:0001".!
  s"docker rmi -f test-elasticsearchdb:latest".!
}

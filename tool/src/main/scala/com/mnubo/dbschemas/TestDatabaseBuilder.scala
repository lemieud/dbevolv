package com.mnubo
package dbschemas

import java.io.File

import com.mnubo.app_util.{MnuboConfiguration, Logging}
import com.mnubo.dbschemas.docker.Docker
import com.typesafe.config.{ConfigParseOptions, ConfigFactory}

import scala.util.control.NonFatal

object TestDatabaseBuilder extends App with Logging {
  val MnuboDockerRegistry = "dockerep-0.mtl.mnubo.com"
  val doPush = if (args contains "push") true else false
  val defaultConfig = ConfigFactory.load(ConfigParseOptions.defaults().setClassLoader(getClass.getClassLoader))
  val config = MnuboConfiguration.loadConfig(
    ConfigFactory
      .parseFile(new File("db.conf"))
      .withFallback(defaultConfig),
    "workstation")
  val dbKind = config.getString("database_kind")
  val schemaName = config.getString("schema_name")
  val imageName = s"test-$schemaName"
  val repositoryName = s"$MnuboDockerRegistry/$imageName"
  val db = Database.databases(dbKind)

  logInfo(s"Starting a fresh test $dbKind $schemaName instance ...")
  val container = Docker.run(db.testDockerBaseImage)

  try {
    logInfo(s"Creating and migrating test database '$schemaName' to latest version ...")
    val report = DatabaseMigrator.migrate(DbMigrationConfig(
      db,
      schemaName,
      Docker.dockerHost,
      container.realPort,
      db.testDockerBaseImage.username,
      db.testDockerBaseImage.password,
      schemaName,
      config.getString("create_database_statement").replace("@@DATABASE_NAME@@", schemaName),
      drop = false,
      None,
      skipSchemaVerification = true,
      applyUpgradesTwice = true,
      config
    ))

    val schemaVersion = report.migratedToVersion

    logInfo(s"Commiting $dbKind $schemaName test instance to $repositoryName:$schemaVersion...")
    Docker.stop(container.id)
    val imageId = Docker.commit(container.id, repositoryName, schemaVersion)

    logInfo(s"Testing rollback procedures...")
    Docker.start(container, db.testDockerBaseImage.isStarted)
    DatabaseMigrator.migrate(DbMigrationConfig(
      db,
      schemaName,
      Docker.dockerHost,
      container.realPort,
      db.testDockerBaseImage.username,
      db.testDockerBaseImage.password,
      schemaName,
      config.getString("create_database_statement").replace("@@DATABASE_NAME@@", schemaName),
      drop = false,
      Some(report.startingVersion),
      skipSchemaVerification = true,
      applyUpgradesTwice = false,
      config
    ))

    if (doPush) {
      logInfo(s"Publishing $dbKind $schemaName test instance to $repositoryName:$schemaVersion ...")
      Docker.push(s"$repositoryName:$schemaVersion")

      logInfo(s"Cleaning up image $imageId ...")
      Docker.removeImage(imageId)
    }
  }
  catch {
    case NonFatal(ex) =>
      logError(s"Test database build failed", ex)
      throw ex
  }
  finally {
    logInfo(s"Cleaning up container ${container.id} ...")
    Docker.stop(container.id)
    Docker.remove(container.id)
  }
}

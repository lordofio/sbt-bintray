package bintray

import bintry.Attr
import sbt.{ AutoPlugin, Credentials, Global, Path, Resolver, Setting, Task }
import sbt.Def.{ Initialize, setting, task, taskDyn }
import sbt.Keys._
import sbt.Path.richFile

object BintrayPlugin extends AutoPlugin {
  import BintrayKeys._
  import InternalBintrayKeys._

  override def requires = sbt.plugins.JvmPlugin
  override def trigger = allRequirements
  
  override def globalSettings: Seq[Setting[_]] = globalPublishSettings
  override def projectSettings: Seq[Setting[_]] = bintraySettings

  object autoImport extends BintrayKeys {
  }

  def bintraySettings: Seq[Setting[_]] =
    bintrayCommonSettings ++ bintrayPublishSettings ++ bintrayQuerySettings

  def bintrayCommonSettings: Seq[Setting[_]] = Seq(
    bintrayChangeCredentials := {
      Bintray.changeCredentials(bintrayCredentialsFile.value)
    },
    bintrayWhoami := {
      Bintray.whoami(bintrayCredentialsFile.value, sLog.value)
    }
  )

  def bintrayQuerySettings: Seq[Setting[_]] = Seq(
    bintrayPackageVersions <<= packageVersionsTask
  )

  def globalPublishSettings: Seq[Setting[_]] = Seq(
    bintrayCredentialsFile in Global := Path.userHome / ".bintray" / ".credentials",
    bintrayOrganization in Global := None
  )

  def bintrayPublishSettings: Seq[Setting[_]] = bintrayCommonSettings ++ Seq(
    bintrayReleaseOnPublish := false,
    bintrayPackage := moduleName.value,
    bintrayRepo := BintrayRepo(bintrayEnsureCredentials.value,
      bintrayOrganization.value,
      bintrayRepository.value),
    // todo: don't force this to be sbt-plugin-releases
    bintrayRepository := {
      if (sbtPlugin.value) Bintray.defaultSbtPluginRepository
      else Bintray.defaultMavenRepository
    },
    publishMavenStyle := {
      if (sbtPlugin.value) false else publishMavenStyle.value
    },
    bintrayVcsUrl := Bintray.resolveVcsUrl.recover { case _ => None }.get,
    bintrayPackageLabels := Nil,
    description in bintray <<= description,
    // note: publishTo may not have dependencies. therefore, we can not rely well on inline overrides
    // for inline credentials resolution we recommend defining bintrayCredentials _before_ mixing in the defaults
    // perhaps we should try overriding something in the publishConfig setting -- https://github.com/sbt/sbt-pgp/blob/master/pgp-plugin/src/main/scala/com/typesafe/sbt/pgp/PgpSettings.scala#L124-L131
    publishTo in bintray <<= publishToBintray,
    resolvers in bintray <<= appendBintrayResolver,
    credentials in bintray := {
      Credentials(bintrayCredentialsFile.value) :: Nil
    },
    bintrayPackageAttributes := {
      if (sbtPlugin.value) Map(AttrNames.sbtPlugin -> Seq(Attr.Boolean(sbtPlugin.value)))
      else Map.empty
    },
    bintrayVersionAttributes := {
      val scalaVersions = crossScalaVersions.value
      val sv = Map(AttrNames.scalas -> scalaVersions.map(Attr.Version(_)))
      if (sbtPlugin.value) sv ++ Map(AttrNames.sbtVersion-> Seq(Attr.Version(sbtVersion.value)))
      else sv
    },
    bintrayOmitLicense := {
      if (sbtPlugin.value) sbtPlugin.value
      else false
    },
    bintrayEnsureLicenses := {
      Bintray.ensureLicenses(licenses.value, bintrayOmitLicense.value)
    },
    bintrayEnsureCredentials := {
      Bintray.ensuredCredentials(bintrayCredentialsFile.value).get
    },
    bintrayEnsureBintrayPackageExists <<= ensurePackageTask,
    bintrayUnpublish := {
      val e1 = bintrayEnsureBintrayPackageExists
      val e2 = bintrayEnsureLicenses
      val repo = bintrayRepo.value
      repo.unpublish(bintrayPackage.value, version.value, sLog.value)
    },
    bintrayRemoteSign := {
      val repo = bintrayRepo.value
      repo.remoteSign(bintrayPackage.value, version.value, sLog.value)
    },
    bintraySyncMavenCentral := {
      val repo = bintrayRepo.value
      repo.syncMavenCentral(bintrayPackage.value, version.value, credentials.value, sLog.value)
    },
    bintrayRelease := {
      val _ = publishVersionAttributesTask.value
      val repo = bintrayRepo.value
      repo.release(bintrayPackage.value, version.value, sLog.value)
    }
  ) ++ Seq(
    resolvers <++= resolvers in bintray,
    credentials <++= credentials in bintray,
    publishTo <<= publishTo in bintray,
    // We attach this to publish configruation, so that publish-signed in pgp plugin can work.
    publishConfiguration <<= publishConfiguration.dependsOn(bintrayEnsureBintrayPackageExists, bintrayEnsureLicenses), 
    publish := dynamicallyPublish.value
  )

  private def dynamicallyPublish: Initialize[Task[Unit]] =
    taskDyn {
      val _ = publish.value
      val isRelease = bintrayReleaseOnPublish.value
      if (isRelease) bintrayRelease
      else warnToRelease
    }

  private def warnToRelease: Initialize[Task[Unit]] =
    task {
      val log = sLog.value
      log.warn("You must run bintrayRelease once all artifacts are staged.")
    }

  private def publishVersionAttributesTask: Initialize[Task[Unit]] =
    task {
      val repo = bintrayRepo.value
      repo.publishVersionAttributes(
        bintrayPackage.value,
        version.value,
        bintrayVersionAttributes.value)
    }

  private def ensurePackageTask: Initialize[Task[Unit]] =
    task {
      val vcs = bintrayVcsUrl.value.getOrElse {
        sys.error("""vcsUrl not defined. assign this with (vcsUrl in bintray) := Some("git@github.com:you/your-repo.git")""")
      }
      val repo = bintrayRepo.value
      repo.ensurePackage(bintrayPackage.value,
        bintrayPackageAttributes.value,
        (description in bintray).value,
        vcs,
        licenses.value,
        bintrayPackageLabels.value)
    }

  /** set a user-specific bintray endpoint for sbt's `publishTo` setting.*/
  private def publishToBintray: Initialize[Option[Resolver]] =
    setting {
      val credsFile = bintrayCredentialsFile.value
      val btyOrg = bintrayOrganization.value
      val repoName = bintrayRepository.value
      // ensure that we have credentials to build a resolver that can publish to bintray
      Bintray.withRepo(credsFile, btyOrg, repoName) { repo =>
        repo.buildPublishResolver(bintrayPackage.value,
          version.value,
          publishMavenStyle.value,
          sbtPlugin.value,
          bintrayReleaseOnPublish.value)
      }
    }

  /** if credentials exist, append a user-specific resolver */
  private def appendBintrayResolver: Initialize[Seq[Resolver]] =
    setting {
      val creds = bintrayCredentialsFile.value
      val btyOrg = bintrayOrganization.value
      val repoName = bintrayRepository.value
      BintrayCredentials.read(creds).fold({ err =>
        println(s"bintray credentials $err is malformed")
        Nil
      }, {
        _.map { case BintrayCredentials(user, _) => Seq(Opts.resolver.repo(btyOrg.getOrElse(user), repoName)) }
         .getOrElse(Nil)
      })
    }

  /** Lists versions of bintray packages corresponding to the current project */
  private def packageVersionsTask: Initialize[Task[Seq[String]]] =
    task {
      val credsFile = bintrayCredentialsFile.value
      val btyOrg = bintrayOrganization.value
      val repoName = bintrayRepository.value
      (Bintray.withRepo(credsFile, btyOrg, repoName) { repo =>
        repo.packageVersions(bintrayPackage.value, sLog.value)
      }).getOrElse(Nil)
    }
}

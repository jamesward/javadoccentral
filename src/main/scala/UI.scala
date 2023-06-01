import MavenCentral.{ArtifactId, GroupId, Version}

import zio.http.html.*

object UI:

  val index: Html =
    form(actionAttr := "/", methodAttr := "get",
      label("GroupId (i.e. ", a(href := "/org.springframework", "org.springframework"), "): ",
        input(nameAttr := "groupId", requiredAttr := "required")
      ),
      " ",
      input(valueAttr := "Go!", typeAttr := "submit"),
    )

  def invalidGroupId(groupId: GroupId): Html =
    form(actionAttr := "/", methodAttr := "get",
      label(
        "GroupId:",
        input(
          nameAttr := "groupId",
          valueAttr := groupId.toString,
          onFocusAttr := "this.setCustomValidity('GroupID is invalid'); this.reportValidity();",
          onInputAttr := "this.setCustomValidity(''); this.reportValidity()",
          autofocusAttr := "autofocus",
          requiredAttr := "required",
        )
      ),
      " ",
      input(valueAttr := "Go!", typeAttr := "submit"),
    )

  def needArtifactId(groupId: GroupId, artifactIds: Seq[ArtifactId]): Html =
    form(actionAttr := s"/$groupId", methodAttr := "get",
      label(
        a(
          href := "/",
          "GroupId"
        ),
        ":",
        input(nameAttr := "groupId", valueAttr := groupId.toString, disabledAttr := "disabled")
      ),
      " ",
      label(
        "ArtifactId:",
        select(
          nameAttr := "artifactId",
          artifactIds.map: artifactId =>
            option(artifactId.toString)
        )
      ),
      " ",
      input(valueAttr := "Go!", typeAttr := "submit"),
    )

  def needVersion(groupId: GroupId, artifactId: ArtifactId, versions: Seq[Version]): Html =
    form(actionAttr := s"/$groupId/$artifactId", methodAttr := "get",
      label(
        a(hrefAttr := "/", "GroupId"),
        ":",
        input(nameAttr := "groupId", valueAttr := groupId.toString, disabledAttr := "disabled")
      ),
      " ",
      label(
        a(hrefAttr := s"/$groupId", "ArtifactId"),
        ":",
        input(nameAttr := "artifactId", valueAttr := artifactId.toString, disabledAttr := "disabled")
      ),
      " ",
      label(
        "Version:",
        select(nameAttr := "version",
          versions.map: version =>
            option(version.toString)
        )
      ),
      " ",
      input(valueAttr := "Go!", typeAttr := "submit")
    )

  def noJavadoc(groupId: GroupId, artifactId: ArtifactId, versions: Seq[Version], version: Version): Html =
    div(
      p(s"Version $version of that artifact does not exist or does not have a JavaDoc jar."),
      needVersion(groupId, artifactId, versions),
    )

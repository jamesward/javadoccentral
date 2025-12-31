import zio.http.template.*
import com.jamesward.zio_mavencentral.MavenCentral
import zio.http.template.Html.fromDomElement

object UI:

  val pageFooter = footer(
    styleAttr := "position: fixed; bottom: 0; left: 0; right: 0; background-color: #adada0; padding: 20px; font-size: 1em;",
    div(
      styleAttr := "display: flex; align-items: center; gap: 8px;",
      svg(
        xmlnsAttr := "http://www.w3.org/2000/svg",
        styleAttr := "height: 1em; width: auto;",
        Dom.attr("viewBox", "0 0 16 16"),
        Dom.raw("""<path fill="currentColor" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"></path>"""),
      ),
      a(
        href := "https://github.com/jamesward/javadoccentral",
        //              styleAttr := "color: #000000; display: flex; align-items: center;",
        styleAttr := "color: #000000;",
        "github.com/jamesward/javadoccentral"
      ),
      span(""),
      svg(
        xmlnsAttr := "http://www.w3.org/2000/svg",
        styleAttr := "height: 1em; width: auto;",
        Dom.attr("viewBox", "0 0 24 24"),
        Dom.raw("""<path d="M15.688 2.343a2.588 2.588 0 00-3.61 0l-9.626 9.44a.863.863 0 01-1.203 0 .823.823 0 010-1.18l9.626-9.44a4.313 4.313 0 016.016 0 4.116 4.116 0 011.204 3.54 4.3 4.3 0 013.609 1.18l.05.05a4.115 4.115 0 010 5.9l-8.706 8.537a.274.274 0 000 .393l1.788 1.754a.823.823 0 010 1.18.863.863 0 01-1.203 0l-1.788-1.753a1.92 1.92 0 010-2.754l8.706-8.538a2.47 2.47 0 000-3.54l-.05-.049a2.588 2.588 0 00-3.607-.003l-7.172 7.034-.002.002-.098.097a.863.863 0 01-1.204 0 .823.823 0 010-1.18l7.273-7.133a2.47 2.47 0 00-.003-3.537z"></path><path d="M14.485 4.703a.823.823 0 000-1.18.863.863 0 00-1.204 0l-7.119 6.982a4.115 4.115 0 000 5.9 4.314 4.314 0 006.016 0l7.12-6.982a.823.823 0 000-1.18.863.863 0 00-1.204 0l-7.119 6.982a2.588 2.588 0 01-3.61 0 2.47 2.47 0 010-3.54l7.12-6.982z"></path>""")
        // <svg fill="currentColor" fill-rule="evenodd" height="1em" style="flex:none;line-height:1" viewBox="0 0 24 24" width="1em" xmlns="http://www.w3.org/2000/svg"><title>ModelContextProtocol</title></svg>
      ),
      span(" MCP server (Streamable HTTP): "),
      code("https://www.javadocs.dev/mcp"),
    )
  )

  val index: Html =
    form(actionAttr := "/", methodAttr := "get",
      label("GroupId (i.e. ", a(href := "/org.springframework", "org.springframework"), "): ",
        input(nameAttr := "groupId", requiredAttr := true)
      ),
      " ",
      input(valueAttr := "Go!", typeAttr := "submit"),
    ) ++ pageFooter

  def invalidGroupId(groupId: MavenCentral.GroupId): Html =
    form(actionAttr := "/", methodAttr := "get",
      label(
        "GroupId:",
        input(
          nameAttr := "groupId",
          valueAttr := groupId.toString,
          onFocusAttr := "this.setCustomValidity('GroupID is invalid'); this.reportValidity();",
          onInputAttr := "this.setCustomValidity(''); this.reportValidity()",
          autofocusAttr := "autofocus",
          requiredAttr := true,
        )
      ),
      " ",
      input(valueAttr := "Go!", typeAttr := "submit"),
    ) ++ pageFooter

  def needArtifactId(groupId: MavenCentral.GroupId, artifactIds: Seq[MavenCentral.ArtifactId]): Html =
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
    ) ++ pageFooter

  def needVersion(groupId: MavenCentral.GroupId, artifactId: MavenCentral.ArtifactId, versions: Seq[MavenCentral.Version]): Html =
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
    ) ++ pageFooter

  def noJavadoc(groupId: MavenCentral.GroupId, artifactId: MavenCentral.ArtifactId, versions: Seq[MavenCentral.Version], version: MavenCentral.Version): Html =
    div(
      p(s"Version $version of that artifact does not exist or does not have a JavaDoc jar."),
      needVersion(groupId, artifactId, versions),
    )

  def symbolSearchResults(query: String, groupArtifacts: Set[MavenCentral.GroupArtifact]): Html =
    div(
      h3("Search results for: ", code(query)),
      if groupArtifacts.isEmpty then
        div(p("No results found - but maybe the library just hasn't been indexed yet?"))
      else
        ul(
          groupArtifacts.toSeq.map:
            groupArtifact =>
              li(a(href := groupArtifact.toPath.toString, s"${groupArtifact.groupId}:${groupArtifact.artifactId}"))
        )
    ) ++ pageFooter

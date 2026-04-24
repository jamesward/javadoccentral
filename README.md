JavaDoc Central
---------------

Artifacts in Maven Central typically provide a JavaDoc Jar that contains the versioned documentation for the artifact.  While IDEs use this to display the docs for a library, it is also sometimes nice to browse the docs in a web browser.  This project is a simple web app that allows you to view the JavaDoc for any artifact in Maven Central.

## Usage Guide

Main page: [javadocs.dev](https://javadocs.dev/)

URL Format: `https://javadocs.dev/GROUP_ID/ARTIFACT_ID/VERSION`

You can specify the `GROUP_ID`, `ARTIFACT_ID`, and `VERSION`, like:  
`https://javadocs.dev/org.webjars/webjars-locator/0.32`

Or the `GROUP_ID` and `ARTIFACT_ID`, like:  
`https://javadocs.dev/org.webjars/webjars-locator`

Or just the `GROUP_ID`, like:  
`https://javadocs.dev/org.webjars`

You can also specify `latest` for the version, like:  
`https://javadocs.dev/org.webjars/webjars-locator/latest`

You can search for symbols with a query parameter, like:
`https://www.javadocs.dev/?WebJarAssetLocator`

## MCP Server

Point your MCP client to `https://www.javadocs.dev/mcp` (Streamable HTTP)

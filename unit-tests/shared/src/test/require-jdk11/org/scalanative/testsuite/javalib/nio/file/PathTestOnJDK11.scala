package org.scalanative.testsuite
package javalib.nio.file

import java.nio.file._
import java.io.File
import java.net.URI

import org.junit.Test
import org.junit.Assert._

import org.scalanative.testsuite.utils.AssertThrows.assertThrows
import org.scalanative.testsuite.utils.Platform.isWindows

class PathTestOnJDK11 {

  @Test def pathOfRelativePathReturnsPathRelativeToCwd(): Unit = {
    val pathString = if (isWindows) raw"foo\bar" else "foo/bar"
    val path = Path.of(pathString)
    val file = new File(pathString)
    assertEquals(pathString, path.toString)
    assertTrue(path.toAbsolutePath.toString != path.toString)
    assertTrue(path.toAbsolutePath.toString.endsWith(path.toString))

    assertTrue(file.getAbsolutePath != path.toString)
    assertEquals(path.toAbsolutePath.toString, file.getAbsolutePath)
  }

  @Test def pathOfAbsolutePathReturnsAnAbsolutePath(): Unit = {
    val pathString = if (isWindows) raw"C:\foo\bar" else "/foo/bar"

    val path = Path.of(pathString)
    val file = new File(pathString)
    assertEquals(pathString, path.toString)
    assertEquals(path.toString, path.toAbsolutePath.toString)

    assertEquals(path.toString, file.getAbsolutePath)
    assertEquals(path.toAbsolutePath.toString, file.getAbsolutePath)
  }

  @Test def pathOfUriThrowsExceptionWhenSchemeIsMissing(): Unit = {
    assertThrows(
      classOf[IllegalArgumentException],
      Path.of(new URI(null, null, null, 0, "foo", null, null))
    )
  }

  @Test def pathOfUriThrowsExceptionWhenSchemeIsNotFile(): Unit = {
    assertThrows(
      classOf[FileSystemNotFoundException],
      Path.of(new URI("http", null, "google.com", 0, "/", null, null))
    )
  }

  @Test def pathOfUriReturnsPathIfSchemeIsFile(): Unit = {
    val pathString1 = if (isWindows) "/C:/foo/bar" else "/foo/bar"
    val expected1 = if (isWindows) raw"C:\foo\bar" else pathString1
    val pathString2 = if (isWindows) "/C:/hello/world" else "/hello/world"
    val expected2 = if (isWindows) raw"C:\hello\world" else pathString2

    val path =
      Path.of(new URI("file", null, null, 0, pathString1, null, null))
    assertEquals(expected1, path.toString)

    val path2 =
      Path.of(new URI("fIlE", null, null, 0, pathString2, null, null))
    assertEquals(expected2, path2.toString)
  }

  @Test def driveRelativePathToStringShownAsAbsolute() = {
    val absolutePath = "/absolute/file"
    val expected = if (isWindows) "\\absolute\\file" else "/absolute/file"

    val path = Path.of(absolutePath)

    assertEquals(expected, path.toString)
  }

  // issue #2433
  @Test def spaceAllowedInPath() = {
    val withSpaces = "space dir/space file"
    val expected = if (isWindows) raw"space dir\space file" else withSpaces

    val path = Path.of("space dir/space file")
    assertEquals(expected, path.toString)
  }

  @Test def joiningEmptyIsEmpty() = {
    assertEquals(Path.of(""), Path.of("", ""))
  }
}

/**
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.fs.gcs;

import com.google.cloud.hadoop.gcsio.MethodOutcome;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * Allows running all tests in GoogleHadoopGlobalRootedFileSystemIntegrationTest against local FS.
 */
@RunWith(JUnit4.class)
public class LocalFileSystemIntegrationTest
    extends HadoopFileSystemTestBase {

  // Root of local FS.
  private static final String ROOT = "file:///tmp/gcs-test/";

  /**
   * Performs initialization once before tests are run.
   */
  @BeforeClass
  public static void beforeAllTests()
      throws IOException {

    // Create a FileSystem instance to access the given HDFS.
    URI uri = null;
    try {
      uri = new URI(ROOT);
    } catch (URISyntaxException e) {
      Assert.fail("Invalid ROOT path: " + ROOT);
    }
    Configuration config = new Configuration();
    config.set("fs.default.name", ROOT);
    ghfs = FileSystem.get(uri, config);
    ghfsFileSystemDescriptor = new FileSystemDescriptor() {
      @Override
      public Path getFileSystemRoot() {
        return new Path(ROOT);
      }

      @Override
      public String getScheme() {
        return getFileSystemRoot().toUri().getScheme();
      }

      @Deprecated
      @Override
      public String getHadoopScheme() {
        return getScheme();
      }
    };

    // The file:/// scheme will secretly use a ChecksumFileSystem under the hood, causing all
    // writes to actually write many more intermediate bytes than the number desired.
    statistics = FileSystemStatistics.IGNORE;

    gcsit = new LocalFileSystemIntegrationTest();
    postCreateInit();
  }

  /**
   * Perform initialization after creating test instances.
   */
  public static void postCreateInit()
      throws IOException {
    HadoopFileSystemTestBase.postCreateInit();
  }

  /**
   * Perform clean-up once after all tests are turn.
   */
  @AfterClass
  public static void afterAllTests()
      throws IOException {
    HadoopFileSystemTestBase.afterAllTests();
  }

  // -----------------------------------------------------------------
  // Tests that exercise behavior defined in HdfsBehavior.
  // -----------------------------------------------------------------

  /**
   * Validates delete().
   */
  @Test @Override
  public void testDelete()
      throws IOException {
    deleteHelper(new HdfsBehavior());
  }

  /**
   * Validates mkdirs().
   */
  @Test @Override
  public void testMkdirs()
      throws IOException, URISyntaxException {
    mkdirsHelper(new HdfsBehavior() {
        @Override
        public MethodOutcome fileAlreadyExistsOutcome() {
          // LocalFileSystem return false when mkdirs is attempted and
          // a file of that path already exists.
          return new MethodOutcome(MethodOutcome.Type.RETURNS_FALSE);
        }
      });
  }

  /**
   * Validates rename().
   */
  @Test @Override
  public void testRename()
      throws IOException {
    try {
      renameHelper(new HdfsBehavior() {
          @Override
          public MethodOutcome renameFileIntoRootOutcome() {
            // LocalFileSystem returns true on rename into root.
            return new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE);
          }

          @Override
          public MethodOutcome renameRootOutcome() {
            // LocalFileSystem throws IOException on rename of root.
            return new MethodOutcome(
                MethodOutcome.Type.THROWS_EXCEPTION, IOException.class);
          }

          @Override
          public MethodOutcome nonExistentSourceOutcome() {
            // LocalFileSystem throws FileNotFoundException on nonexistent src.
            return new MethodOutcome(
                MethodOutcome.Type.THROWS_EXCEPTION, FileNotFoundException.class);
          }

          @Override
          public MethodOutcome destinationFileExistsSrcIsFileOutcome() {
            // LocalFileSystem returns true if dst already exists, is a file, and src is also a
            // file.
            return new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE);
          }

          @Override
          public MethodOutcome nonExistentDestinationFileParentOutcome() {
            // LocalFileSystem throws FileNotFoundException if a parent of a file dst doesn't exist.
            return new MethodOutcome(
                MethodOutcome.Type.THROWS_EXCEPTION, FileNotFoundException.class);
          }

          @Override
          public MethodOutcome nonExistentDestinationDirectoryParentOutcome() {
            // LocalFileSystem returns true if a parent of a directory dst doesn't exist.
            return new MethodOutcome(MethodOutcome.Type.RETURNS_TRUE);
          }
        });
    } catch (AssertionError ae) {
      // LocalFileSystem behaves differently for the case where dst is an existing directory,
      // and src is a directory with a file underneath it. GHFS places the src directory
      // as a subdirectory into dst; LocalFileSystem just clobbers dst directly.
      // NB: This is *not* how command-line "mv" works; "mv" works like GHFS.
      boolean hadUnexpectedError = false;
      List<Throwable> unexpectedErrors = new ArrayList<>();
      for (Throwable t : ae.getSuppressed()) {
        if (!t.getMessage().matches(".*destination is a dir that exists and non-empty: 2.*") &&
            !t.getMessage().matches(".*src is a directory with a multi-level subdirectory; "
                + "dst is a directory which exists..*")) {
          unexpectedErrors.add(t);
        }
      }
      if (!unexpectedErrors.isEmpty()) {
        AssertionError errors = new AssertionError();
        for (Throwable t : unexpectedErrors) {
          errors.addSuppressed(t);
        }
        throw errors;
      }
    }
  }

  // -----------------------------------------------------------------

  @Override
  protected long getExpectedObjectSize(String objectName, boolean expectedToExist)
      throws UnsupportedEncodingException {
    // For file:/ scheme directories which are expected to exist, we have no idea what the
    // filesystem will report; usually it's 4096 but memfs in /tmp can be any number of possible
    // sizes; return Long.MIN_VALUE for "don't know". Otherwise, delegate to superclass.
    boolean isDir = objectName == null || objectName.endsWith("/");
    if (expectedToExist && isDir) {
      return Long.MIN_VALUE;
    } else {
      return super.getExpectedObjectSize(objectName, expectedToExist);
    }
  }

  @Test @Override
  public void testGetDefaultReplication()
      throws IOException {
    // TODO(user): Abstract out a virtual method per concrete test class for expected replication.
  }
}

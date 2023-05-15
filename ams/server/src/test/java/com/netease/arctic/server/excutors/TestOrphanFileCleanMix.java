/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.server.excutors;

import com.netease.arctic.BasicTableTestHelper;
import com.netease.arctic.ams.api.TableFormat;
import com.netease.arctic.catalog.BasicCatalogTestHelper;
import com.netease.arctic.catalog.TableTestBase;
import com.netease.arctic.data.ChangeAction;
import com.netease.arctic.io.DataTestHelpers;
import com.netease.arctic.server.dashboard.utils.AmsUtil;
import com.netease.arctic.server.table.ServerTableIdentifier;
import com.netease.arctic.server.table.TableRuntime;
import com.netease.arctic.server.table.executor.OrphanFilesCleaningExecutor;
import com.netease.arctic.table.KeyedTable;
import com.netease.arctic.table.TableProperties;
import com.netease.arctic.table.UnkeyedTable;
import com.netease.arctic.utils.TableFileUtil;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.io.OutputFile;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.netease.arctic.server.table.executor.OrphanFilesCleaningExecutor.DATA_FOLDER_NAME;
import static com.netease.arctic.server.table.executor.OrphanFilesCleaningExecutor.FLINK_JOB_ID;

@RunWith(Parameterized.class)
public class TestOrphanFileCleanMix extends TableTestBase {

  public TestOrphanFileCleanMix(boolean ifKeyed, boolean ifPartitioned) {
    super(new BasicCatalogTestHelper(TableFormat.MIXED_ICEBERG),
        new BasicTableTestHelper(ifKeyed, ifPartitioned));
  }

  @Parameterized.Parameters(name = "ifKeyed = {0}, ifPartitioned = {1}")
  public static Object[][] parameters() {
    return new Object[][]{
        {true, true},
        {true, false},
        {false, true},
        {false, false}};
  }

  private static OrphanFilesCleaningExecutor orphanFilesCleaningExecutor;
  private static TableRuntime tableRuntime;

  @Before
  public void mock() {
    orphanFilesCleaningExecutor = Mockito.mock(OrphanFilesCleaningExecutor.class);
    tableRuntime = Mockito.mock(TableRuntime.class);
    Mockito.when(tableRuntime.loadTable()).thenReturn(getArcticTable());
    Mockito.when(tableRuntime.getTableIdentifier()).thenReturn(
        ServerTableIdentifier.of(AmsUtil.toTableIdentifier(getArcticTable().id())));
    Mockito.doCallRealMethod().when(orphanFilesCleaningExecutor).execute(tableRuntime);
  }

  @Test
  public void orphanDataFileClean() throws IOException {
    ExecutorTestUtil.writeAndCommitBaseAndChange(getArcticTable());

    UnkeyedTable baseTable = isKeyedTable() ?
        getArcticTable().asKeyedTable().baseTable() : getArcticTable().asUnkeyedTable();
    String baseOrphanFileDir = baseTable.location() +
        File.separator + DATA_FOLDER_NAME + File.separator + "testLocation";
    String baseOrphanFilePath = baseOrphanFileDir + File.separator + "orphan.parquet";
    OutputFile baseOrphanDataFile = getArcticTable().io().newOutputFile(baseOrphanFilePath);
    baseOrphanDataFile.createOrOverwrite().close();

    String changeOrphanFilePath = isKeyedTable() ? getArcticTable().asKeyedTable().changeTable().location() +
        File.separator + DATA_FOLDER_NAME + File.separator + "orphan.parquet" : "";
    if (isKeyedTable()) {
      OutputFile changeOrphanDataFile = getArcticTable().io().newOutputFile(changeOrphanFilePath);
      changeOrphanDataFile.createOrOverwrite().close();
      Assert.assertTrue(getArcticTable().io().exists(changeOrphanFilePath));
    }

    Assert.assertTrue(getArcticTable().io().exists(baseOrphanFileDir));
    Assert.assertTrue(getArcticTable().io().exists(baseOrphanFilePath));


    orphanFilesCleaningExecutor.execute(tableRuntime);
    Assert.assertTrue(getArcticTable().io().exists(baseOrphanFileDir));
    Assert.assertTrue(getArcticTable().io().exists(baseOrphanFilePath));
    if (isKeyedTable()) {
      Assert.assertTrue(getArcticTable().io().exists(changeOrphanFilePath));
    }


    getArcticTable().updateProperties()
        .set(TableProperties.MIN_ORPHAN_FILE_EXISTING_TIME, "0")
        .set(TableProperties.ENABLE_ORPHAN_CLEAN, "true")
        .commit();
    orphanFilesCleaningExecutor.execute(tableRuntime);

    Assert.assertFalse(getArcticTable().io().exists(baseOrphanFileDir));
    Assert.assertFalse(getArcticTable().io().exists(baseOrphanFilePath));
    if (isKeyedTable()) {
      Assert.assertFalse(getArcticTable().io().exists(changeOrphanFilePath));
    }
    for (FileScanTask task : baseTable.newScan().planFiles()) {
      Assert.assertTrue(getArcticTable().io().exists(task.file().path().toString()));
    }
    if (isKeyedTable()) {
      for (FileScanTask task : getArcticTable().asKeyedTable().changeTable().newScan().planFiles()) {
        Assert.assertTrue(getArcticTable().io().exists(task.file().path().toString()));
      }
    }
  }

  @Test
  public void orphanChangeDataFileInBaseClean() throws IOException {
    Assume.assumeTrue(isKeyedTable());
    KeyedTable testKeyedTable = getArcticTable().asKeyedTable();
    List<DataFile> dataFiles = DataTestHelpers.writeChangeStore(
        testKeyedTable, 1, ChangeAction.INSERT, ExecutorTestUtil.createRecords(1, 100), false);
    Set<String> pathAll = new HashSet<>();
    Set<String> fileInBaseStore = new HashSet<>();
    Set<String> fileOnlyInChangeLocation = new HashSet<>();

    AppendFiles appendFiles = testKeyedTable.asKeyedTable().baseTable().newAppend();

    for (int i = 0; i < dataFiles.size(); i++) {
      DataFile dataFile = dataFiles.get(i);
      pathAll.add(TableFileUtil.getUriPath(dataFile.path().toString()));
      if (i == 0) {
        appendFiles.appendFile(dataFile).commit();
        fileInBaseStore.add(TableFileUtil.getUriPath(dataFile.path().toString()));
      } else {
        fileOnlyInChangeLocation.add(TableFileUtil.getUriPath(dataFile.path().toString()));
      }
    }
    pathAll.forEach(path -> Assert.assertTrue(testKeyedTable.io().exists(path)));

    orphanFilesCleaningExecutor.cleanContentFiles(testKeyedTable, System.currentTimeMillis());
    fileInBaseStore.forEach(path -> Assert.assertTrue(testKeyedTable.io().exists(path)));
    fileOnlyInChangeLocation.forEach(path -> Assert.assertFalse(testKeyedTable.io().exists(path)));
  }

  @Test
  public void orphanMetadataFileClean() throws IOException {
    ExecutorTestUtil.writeAndCommitBaseAndHive(getArcticTable(), 1, false);

    UnkeyedTable baseTable = isKeyedTable() ?
        getArcticTable().asKeyedTable().baseTable() : getArcticTable().asUnkeyedTable();
    String baseOrphanFilePath = baseTable.location() + File.separator + "metadata" +
        File.separator + "orphan.avro";

    String changeOrphanFilePath = isKeyedTable() ? getArcticTable().asKeyedTable().changeTable().location() +
        File.separator + "metadata" + File.separator + "orphan.avro" : "";

    OutputFile baseOrphanDataFile = getArcticTable().io().newOutputFile(baseOrphanFilePath);
    baseOrphanDataFile.createOrOverwrite().close();

    String changeInvalidMetadataJson = isKeyedTable() ? getArcticTable().asKeyedTable().changeTable().location() +
            File.separator + "metadata" + File.separator + "v0.metadata.json" : "";
    if (isKeyedTable()) {
      OutputFile changeOrphanDataFile = getArcticTable().io().newOutputFile(changeOrphanFilePath);
      changeOrphanDataFile.createOrOverwrite().close();
      getArcticTable().io().newOutputFile(changeInvalidMetadataJson).createOrOverwrite().close();
    }

    Assert.assertTrue(getArcticTable().io().exists(baseOrphanFilePath));
    if (isKeyedTable()) {
      Assert.assertTrue(getArcticTable().io().exists(changeOrphanFilePath));
      Assert.assertTrue(getArcticTable().io().exists(changeInvalidMetadataJson));
    }

    orphanFilesCleaningExecutor.cleanMetadata(getArcticTable(), System.currentTimeMillis());
    Assert.assertFalse(getArcticTable().io().exists(baseOrphanFilePath));
    if (isKeyedTable()) {
      Assert.assertFalse(getArcticTable().io().exists(changeOrphanFilePath));
      Assert.assertFalse(getArcticTable().io().exists(changeInvalidMetadataJson));
    }
    ExecutorTestUtil.assertMetadataExists(getArcticTable());
  }

  @Test
  public void notDeleteFlinkTemporaryFile() throws IOException {
    ExecutorTestUtil.writeAndCommitBaseAndHive(getArcticTable(), 1, false);
    String flinkJobId = "flinkJobTest";
    String fakeFlinkJobId = "fakeFlinkJobTest";

    UnkeyedTable baseTable = isKeyedTable() ?
        getArcticTable().asKeyedTable().baseTable() : getArcticTable().asUnkeyedTable();
    String baseOrphanFilePath = baseTable.location() + File.separator + "metadata" +
        File.separator + flinkJobId + "orphan.avro";

    String changeOrphanFilePath = isKeyedTable() ? getArcticTable().asKeyedTable().changeTable().location() +
        File.separator + "metadata" + File.separator + flinkJobId + "orphan.avro" : "";
    String fakeChangeOrphanFilePath = isKeyedTable() ? getArcticTable().asKeyedTable().changeTable().location() +
        File.separator + "metadata" + File.separator + fakeFlinkJobId + "orphan.avro" : "";
    String changeInvalidMetadataJson = isKeyedTable() ? getArcticTable().asKeyedTable().changeTable().location() +
        File.separator + "metadata" + File.separator + "v0.metadata.json" : "";

    OutputFile baseOrphanDataFile = getArcticTable().io().newOutputFile(baseOrphanFilePath);
    baseOrphanDataFile.createOrOverwrite().close();
    if (isKeyedTable()) {
      OutputFile changeOrphanDataFile = getArcticTable().io().newOutputFile(changeOrphanFilePath);
      changeOrphanDataFile.createOrOverwrite().close();
      OutputFile fakeChangeOrphanDataFile = getArcticTable().io().newOutputFile(fakeChangeOrphanFilePath);
      fakeChangeOrphanDataFile.createOrOverwrite().close();

      getArcticTable().io().newOutputFile(changeInvalidMetadataJson).createOrOverwrite().close();
      AppendFiles appendFiles = getArcticTable().asKeyedTable().changeTable().newAppend();
      appendFiles.set(FLINK_JOB_ID, fakeFlinkJobId);
      appendFiles.commit();
      // set flink.job-id to change table
      AppendFiles appendFiles2 = getArcticTable().asKeyedTable().changeTable().newAppend();
      appendFiles2.set(FLINK_JOB_ID, flinkJobId);
      appendFiles2.commit();
    }

    Assert.assertTrue(getArcticTable().io().exists(baseOrphanFilePath));
    if (isKeyedTable()) {
      Assert.assertTrue(getArcticTable().io().exists(changeOrphanFilePath));
      Assert.assertTrue(getArcticTable().io().exists(fakeChangeOrphanFilePath));
      Assert.assertTrue(getArcticTable().io().exists(changeInvalidMetadataJson));
    }

    orphanFilesCleaningExecutor.cleanMetadata(getArcticTable(), System.currentTimeMillis());
    Assert.assertFalse(getArcticTable().io().exists(baseOrphanFilePath));
    if (isKeyedTable()) {
      // files whose file name starts with flink.job-id should not be deleted
      Assert.assertTrue(getArcticTable().io().exists(changeOrphanFilePath));
      Assert.assertFalse(getArcticTable().io().exists(fakeChangeOrphanFilePath));
      Assert.assertFalse(getArcticTable().io().exists(changeInvalidMetadataJson));
    }

    ExecutorTestUtil.assertMetadataExists(getArcticTable());
  }

}
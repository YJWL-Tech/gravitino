/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.hadoop.integration.test;

import static com.datastrato.gravitino.catalog.hadoop.HadoopCatalogPropertiesMetadata.CHECK_UNIQUE_STORAGE_LOCATION_SCHEME;
import static com.datastrato.gravitino.connector.BaseCatalog.CATALOG_BYPASS_PREFIX;

import com.datastrato.gravitino.Catalog;
import com.datastrato.gravitino.NameIdentifier;
import com.datastrato.gravitino.Namespace;
import com.datastrato.gravitino.client.GravitinoMetalake;
import com.datastrato.gravitino.exceptions.FilesetAlreadyExistsException;
import com.datastrato.gravitino.exceptions.NoSuchFilesetException;
import com.datastrato.gravitino.file.Fileset;
import com.datastrato.gravitino.file.FilesetChange;
import com.datastrato.gravitino.integration.test.container.ContainerSuite;
import com.datastrato.gravitino.integration.test.container.HiveContainer;
import com.datastrato.gravitino.integration.test.util.AbstractIT;
import com.datastrato.gravitino.integration.test.util.GravitinoITUtils;
import com.datastrato.gravitino.properties.FilesetProperties;
import com.datastrato.gravitino.rel.Schema;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.AclEntry;
import org.apache.hadoop.fs.permission.AclStatus;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Tag("gravitino-docker-it")
public class HadoopCatalogIT extends AbstractIT {
  private static final Logger LOG = LoggerFactory.getLogger(HadoopCatalogIT.class);
  private static final ContainerSuite containerSuite = ContainerSuite.getInstance();

  public static final String metalakeName =
      GravitinoITUtils.genRandomName("CatalogFilesetIT_metalake");
  public static final String catalogName =
      GravitinoITUtils.genRandomName("CatalogFilesetIT_catalog");
  public static final String SCHEMA_PREFIX = "CatalogFilesetIT_schema";
  public static final String schemaName = GravitinoITUtils.genRandomName(SCHEMA_PREFIX);
  private static final String provider = "hadoop";
  private static GravitinoMetalake metalake;
  private static Catalog catalog;
  private static FileSystem hdfs;
  private static String defaultBaseLocation;

  @BeforeAll
  public static void setup() throws IOException {
    containerSuite.startHiveContainer();

    Configuration conf = new Configuration();
    conf.set("fs.defaultFS", defaultBaseLocation());
    hdfs = FileSystem.get(conf);

    createMetalake();
    createCatalog();
    createSchema();
  }

  @AfterAll
  public static void stop() throws IOException {
    client.dropMetalake(NameIdentifier.of(metalakeName));

    if (hdfs != null) {
      hdfs.close();
    }

    try {
      closer.close();
    } catch (Exception e) {
      LOG.error("Failed to close CloseableGroup", e);
    }
  }

  private static void createMetalake() {
    GravitinoMetalake[] gravitinoMetalakes = client.listMetalakes();
    Assertions.assertEquals(0, gravitinoMetalakes.length);

    GravitinoMetalake createdMetalake =
        client.createMetalake(NameIdentifier.of(metalakeName), "comment", Collections.emptyMap());
    GravitinoMetalake loadMetalake = client.loadMetalake(NameIdentifier.of(metalakeName));
    Assertions.assertEquals(createdMetalake, loadMetalake);

    metalake = loadMetalake;
  }

  private static void createCatalog() {
    metalake.createCatalog(
        NameIdentifier.of(metalakeName, catalogName),
        Catalog.Type.FILESET,
        provider,
        "comment",
        ImmutableMap.of(CATALOG_BYPASS_PREFIX + CHECK_UNIQUE_STORAGE_LOCATION_SCHEME, "false"));

    catalog = metalake.loadCatalog(NameIdentifier.of(metalakeName, catalogName));
  }

  private static void createSchema() {
    NameIdentifier ident = NameIdentifier.of(metalakeName, catalogName, schemaName);
    Map<String, String> properties = Maps.newHashMap();
    properties.put("key1", "val1");
    properties.put("key2", "val2");
    properties.put("location", defaultBaseLocation());
    String comment = "comment";

    catalog.asSchemas().createSchema(ident, comment, properties);
    Schema loadSchema = catalog.asSchemas().loadSchema(ident);
    Assertions.assertEquals(schemaName, loadSchema.name());
    Assertions.assertEquals(comment, loadSchema.comment());
    Assertions.assertEquals("val1", loadSchema.properties().get("key1"));
    Assertions.assertEquals("val2", loadSchema.properties().get("key2"));
    Assertions.assertNotNull(loadSchema.properties().get("location"));
  }

  private static void dropSchema() {
    NameIdentifier ident = NameIdentifier.of(metalakeName, catalogName, schemaName);
    catalog.asSchemas().dropSchema(ident, true);
    Assertions.assertFalse(catalog.asSchemas().schemaExists(ident));
  }

  @Test
  public void testCreateFileset() throws IOException {
    // create fileset
    String filesetName = "test_create_fileset";
    String storageLocation = storageLocation(filesetName);
    Assertions.assertFalse(
        hdfs.exists(new Path(storageLocation)), "storage location should not exists");
    Fileset fileset =
        createFileset(
            filesetName,
            "comment",
            Fileset.Type.MANAGED,
            storageLocation,
            ImmutableMap.of("k1", "v1"));

    // verify fileset is created
    assertFilesetExists(filesetName);
    Assertions.assertNotNull(fileset, "fileset should be created");
    Assertions.assertEquals("comment", fileset.comment());
    Assertions.assertEquals(Fileset.Type.MANAGED, fileset.type());
    Assertions.assertEquals(storageLocation, fileset.storageLocation());
    Assertions.assertEquals(5, fileset.properties().size());
    Assertions.assertEquals("v1", fileset.properties().get("k1"));

    // test create a fileset that already exist
    Assertions.assertThrows(
        FilesetAlreadyExistsException.class,
        () ->
            createFileset(
                filesetName,
                "comment",
                Fileset.Type.MANAGED,
                storageLocation,
                ImmutableMap.of("k1", "v1")),
        "Should throw FilesetAlreadyExistsException when fileset already exists");

    // create fileset with null storage location
    String filesetName2 = "test_create_fileset_no_storage_location";

    // internal version must have storage location, so it will throw exception
    // while creating fileset
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> {
          createFileset(filesetName2, null, Fileset.Type.MANAGED, null, null);
        },
        "Should throw IllegalArgumentException when storage location is null");

    // as stated above,
    // internal version will not create fileset with null storage location
    // therefore, comment out the following assertion

    //    assertFilesetExists(filesetName2);
    //    Assertions.assertNotNull(fileset2, "fileset should be created");
    //    Assertions.assertNull(fileset2.comment(), "comment should be null");
    //    Assertions.assertEquals(Fileset.Type.MANAGED, fileset2.type(), "type should be MANAGED");
    //    Assertions.assertEquals(
    //        storageLocation(filesetName2),
    //        fileset2.storageLocation(),
    //        "storage location should be created");
    //    Assertions.assertEquals(ImmutableMap.of(), fileset2.properties(), "properties should be
    // empty");

    // create fileset with null fileset name
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            createFileset(
                null,
                "comment",
                Fileset.Type.MANAGED,
                storageLocation,
                ImmutableMap.of("k1", "v1")),
        "Should throw IllegalArgumentException when fileset name is null");

    // create fileset with null fileset type
    String filesetName3 = "test_create_fileset_no_type";
    String storageLocation3 = storageLocation(filesetName3);
    Fileset fileset3 =
        createFileset(filesetName3, "comment", null, storageLocation3, ImmutableMap.of("k1", "v1"));
    assertFilesetExists(filesetName3);
    Assertions.assertEquals(
        Fileset.Type.MANAGED, fileset3.type(), "fileset type should be MANAGED by default");
  }

  @Test
  public void testCreateFilesetWithChineseComment() throws IOException {
    // create fileset
    String filesetName = "test_create_fileset";
    String storageLocation = storageLocation(filesetName);
    Assertions.assertFalse(
        hdfs.exists(new Path(storageLocation)), "storage location should not exists");
    Fileset fileset =
        createFileset(
            filesetName,
            "这是中文comment",
            Fileset.Type.MANAGED,
            storageLocation,
            ImmutableMap.of("k1", "v1"));

    // verify fileset is created
    assertFilesetExists(filesetName);
    Assertions.assertNotNull(fileset, "fileset should be created");
    Assertions.assertEquals("这是中文comment", fileset.comment());
    Assertions.assertEquals(Fileset.Type.MANAGED, fileset.type());
    Assertions.assertEquals(storageLocation, fileset.storageLocation());
    Assertions.assertEquals(5, fileset.properties().size());
    Assertions.assertEquals("v1", fileset.properties().get("k1"));
  }

  @Test
  public void testExternalFileset() throws IOException {
    // create fileset
    String filesetName = "test_external_fileset";
    String storageLocation = storageLocation(filesetName);

    // internal version create EXTERNAL fileset need exist file dir
    Path filesetPath = new Path(storageLocation);
    FileSystem fs = filesetPath.getFileSystem(new Configuration());
    fs.mkdirs(filesetPath);

    Fileset fileset =
        createFileset(
            filesetName,
            "comment",
            Fileset.Type.EXTERNAL,
            storageLocation,
            ImmutableMap.of("k1", "v1"));

    // verify fileset is created
    assertFilesetExists(filesetName);
    Assertions.assertNotNull(fileset, "fileset should be created");
    Assertions.assertEquals("comment", fileset.comment());
    Assertions.assertEquals(Fileset.Type.EXTERNAL, fileset.type());
    Assertions.assertEquals(storageLocation, fileset.storageLocation());
    Assertions.assertEquals(5, fileset.properties().size());
    Assertions.assertEquals("v1", fileset.properties().get("k1"));
    Assertions.assertTrue(
        hdfs.exists(new Path(storageLocation)), "storage location should be created");

    // create fileset with storage location that not exist
    String filesetName2 = "test_external_fileset_no_exist";
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            createFileset(
                filesetName2, "comment", Fileset.Type.EXTERNAL, null, ImmutableMap.of("k1", "v1")),
        "Should throw IllegalArgumentException when storage location is null");
  }

  @ParameterizedTest
  @MethodSource("filesetTypes")
  void testCreateLoadAndDropFilesetWithMultipleLocs(Fileset.Type type) throws IOException {
    // create fileset
    String filesetName = "test_fileset_with_multi_locs_" + type.name();
    String storageLocation = storageLocation(filesetName);
    String backupStorageLocation = storageLocation(filesetName + "_bak");

    if (type == Fileset.Type.EXTERNAL) {
      // we can skip this step for backup storage locations since the catalog will create them
      // automatically
      Arrays.asList(storageLocation).forEach(location -> createStorageLocation(new Path(location)));
    }

    // create a Fileset with backup storage location.
    Map<String, String> props =
        ImmutableMap.of(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1, backupStorageLocation);
    Fileset createdFileset = createFileset(filesetName, "comment", type, storageLocation, props);
    Assertions.assertEquals(storageLocation, createdFileset.storageLocation());
    Assertions.assertEquals(
        backupStorageLocation,
        createdFileset.properties().get(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1));

    // create external fileset with a backup storage location already mounted, and it will throw a
    // RuntimeException.
    if (type == Fileset.Type.EXTERNAL) {
      String filesetName1 = "test_fileset_with_mounted_path_" + type.name();
      String storageLocation1 = storageLocation(filesetName1);
      Assertions.assertThrowsExactly(
          RuntimeException.class,
          () -> createFileset(filesetName1, "comment", type, storageLocation1, props));
    }

    // Load fileset with backup storage location properties
    Fileset laodedFileset = loadFileset(filesetName);
    Assertions.assertEquals(storageLocation, laodedFileset.storageLocation());
    Assertions.assertEquals(
        backupStorageLocation,
        laodedFileset.properties().get(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1));

    // Drop fileset
    assertFilesetExists(filesetName);
    Assertions.assertTrue(dropFileset(filesetName));
    if (type == Fileset.Type.EXTERNAL) {
      Arrays.asList(storageLocation, backupStorageLocation)
          .forEach(
              location -> Assertions.assertTrue(checkStorageLocationExists(new Path(location))));
    } else {
      Arrays.asList(storageLocation, backupStorageLocation)
          .forEach(
              location -> Assertions.assertFalse(checkStorageLocationExists(new Path(location))));
    }
  }

  @ParameterizedTest
  @MethodSource("filesetTypes")
  void testStorageLocationFilesetChanges(Fileset.Type type) {
    String filesetName = "test_fileset_storage_loc_fileset_change_" + type.name();
    String comment = "test_comment";
    String storageLocation = storageLocation(filesetName);
    String backupLoc1 = storageLocation(filesetName + "_bak_1");
    String backupLoc2 = storageLocation(filesetName + "_bak_2");
    String backupLoc1_new = storageLocation(filesetName + "_bak_1_new");
    String backupLoc2_new = storageLocation(filesetName + "_bak_2_new");
    String newStorageLocation = storageLocation(filesetName + "_new");

    FilesetChange change1 =
        FilesetChange.addBackupStorageLocation(
            FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1, backupLoc1);
    FilesetChange change2 =
        FilesetChange.addBackupStorageLocation(
            FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 2, backupLoc2);
    FilesetChange change3 =
        FilesetChange.updateBackupStorageLocation(
            FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1, backupLoc1_new);
    FilesetChange change4 =
        FilesetChange.updateBackupStorageLocation(
            FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 2, backupLoc2_new);
    FilesetChange change5 =
        FilesetChange.switchBackupStorageLocation(
            FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 2,
            FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1);
    FilesetChange change6 =
        FilesetChange.switchPrimaryAndBackupStorageLocation(
            FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1);
    FilesetChange change7 =
        FilesetChange.removeBackupStorageLocation(
            FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1);
    FilesetChange change8 =
        FilesetChange.removeBackupStorageLocation(
            FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 2);
    FilesetChange change9 = FilesetChange.updatePrimaryStorageLocation(newStorageLocation);
    FilesetChange change10 = FilesetChange.updatePrimaryStorageLocation(storageLocation);

    if (type == Fileset.Type.EXTERNAL) {
      Arrays.asList(new Path(storageLocation), new Path(newStorageLocation))
          .forEach(this::createStorageLocation);
    }

    Fileset fileset = createFileset(filesetName, comment, type, storageLocation, ImmutableMap.of());

    Fileset fileset1 = alterFileset(filesetName, change1);
    Assertions.assertEquals(filesetName, fileset1.name());
    Assertions.assertEquals(type, fileset1.type());
    Assertions.assertEquals("test_comment", fileset1.comment());
    Assertions.assertEquals(fileset.storageLocation(), fileset1.storageLocation());
    Map<String, String> props1 = fileset1.properties();
    Assertions.assertTrue(props1.containsKey(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1));
    Assertions.assertEquals(
        backupLoc1, props1.get(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1));

    Fileset fileset2 = alterFileset(filesetName, change2);
    Assertions.assertEquals(filesetName, fileset2.name());
    Assertions.assertEquals(type, fileset2.type());
    Assertions.assertEquals("test_comment", fileset2.comment());
    Assertions.assertEquals(fileset.storageLocation(), fileset2.storageLocation());
    Map<String, String> props2 = fileset2.properties();
    Assertions.assertTrue(props2.containsKey(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1));
    Assertions.assertEquals(
        backupLoc1, props2.get(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1));
    Assertions.assertTrue(props2.containsKey(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 2));
    Assertions.assertEquals(
        backupLoc2, props2.get(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 2));

    Fileset fileset3 = alterFileset(filesetName, change3);
    Assertions.assertEquals(filesetName, fileset3.name());
    Assertions.assertEquals(type, fileset3.type());
    Assertions.assertEquals("test_comment", fileset3.comment());
    Assertions.assertEquals(fileset.storageLocation(), fileset3.storageLocation());
    Map<String, String> props3 = fileset3.properties();
    Assertions.assertTrue(props3.containsKey(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1));
    Assertions.assertEquals(
        backupLoc1_new, props3.get(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1));
    Assertions.assertTrue(props3.containsKey(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 2));
    Assertions.assertEquals(
        backupLoc2, props3.get(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 2));

    Fileset fileset4 = alterFileset(filesetName, change4);
    Assertions.assertEquals(filesetName, fileset4.name());
    Assertions.assertEquals(type, fileset4.type());
    Assertions.assertEquals("test_comment", fileset4.comment());
    Assertions.assertEquals(fileset.storageLocation(), fileset4.storageLocation());
    Map<String, String> props4 = fileset4.properties();
    Assertions.assertTrue(props4.containsKey(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1));
    Assertions.assertEquals(
        backupLoc1_new, props4.get(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1));
    Assertions.assertTrue(props4.containsKey(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 2));
    Assertions.assertEquals(
        backupLoc2_new, props4.get(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 2));

    Fileset fileset5 = alterFileset(filesetName, change5);
    Assertions.assertEquals(filesetName, fileset5.name());
    Assertions.assertEquals(type, fileset5.type());
    Assertions.assertEquals("test_comment", fileset5.comment());
    Assertions.assertEquals(fileset.storageLocation(), fileset5.storageLocation());
    Map<String, String> props5 = fileset5.properties();
    Assertions.assertTrue(props5.containsKey(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1));
    Assertions.assertEquals(
        backupLoc2_new, props5.get(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1));
    Assertions.assertTrue(props5.containsKey(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 2));
    Assertions.assertEquals(
        backupLoc1_new, props5.get(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 2));

    Fileset fileset6 = alterFileset(filesetName, change6);
    Assertions.assertEquals(filesetName, fileset6.name());
    Assertions.assertEquals(type, fileset6.type());
    Assertions.assertEquals("test_comment", fileset6.comment());
    Assertions.assertEquals(backupLoc2_new, fileset6.storageLocation());
    Map<String, String> props6 = fileset6.properties();
    Assertions.assertTrue(props6.containsKey(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1));
    Assertions.assertEquals(
        fileset.storageLocation(), props6.get(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1));
    Assertions.assertTrue(props6.containsKey(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 2));
    Assertions.assertEquals(
        backupLoc1_new, props6.get(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 2));

    Fileset fileset7 = alterFileset(filesetName, change7);
    Assertions.assertEquals(filesetName, fileset7.name());
    Assertions.assertEquals(type, fileset7.type());
    Assertions.assertEquals("test_comment", fileset7.comment());
    Assertions.assertEquals(backupLoc2_new, fileset7.storageLocation());
    Map<String, String> props7 = fileset7.properties();
    Assertions.assertFalse(props7.containsKey(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1));
    Assertions.assertTrue(props7.containsKey(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 2));
    Assertions.assertEquals(
        backupLoc1_new, props7.get(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 2));

    Fileset fileset8 = alterFileset(filesetName, change8);
    Assertions.assertEquals(filesetName, fileset8.name());
    Assertions.assertEquals(type, fileset8.type());
    Assertions.assertEquals("test_comment", fileset8.comment());
    Assertions.assertEquals(backupLoc2_new, fileset8.storageLocation());
    Map<String, String> props8 = fileset8.properties();
    Assertions.assertFalse(props8.containsKey(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1));
    Assertions.assertFalse(props8.containsKey(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 2));

    Fileset fileset9 = alterFileset(filesetName, change9);
    Assertions.assertEquals(filesetName, fileset9.name());
    Assertions.assertEquals(type, fileset9.type());
    Assertions.assertEquals("test_comment", fileset9.comment());
    Assertions.assertEquals(newStorageLocation, fileset9.storageLocation());
    alterFileset(filesetName, change10);

    Fileset fileset10 = alterFileset(filesetName, change1);
    Assertions.assertEquals(filesetName, fileset10.name());
    Assertions.assertEquals(type, fileset10.type());
    Assertions.assertEquals("test_comment", fileset10.comment());
    Assertions.assertEquals(storageLocation, fileset10.storageLocation());
    Map<String, String> props10 = fileset10.properties();
    Assertions.assertTrue(props10.containsKey(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1));
    Assertions.assertEquals(
        backupLoc1, props10.get(FilesetProperties.BACKUP_STORAGE_LOCATION_KEY + 1));

    createStorageLocation(new Path(backupLoc1 + "/subDir"));
    RuntimeException runtimeException =
        Assertions.assertThrowsExactly(
            RuntimeException.class, () -> alterFileset(filesetName, change3));
    Assertions.assertTrue(runtimeException.getMessage().contains("where data exists for Fileset"));

    runtimeException =
        Assertions.assertThrowsExactly(
            RuntimeException.class, () -> alterFileset(filesetName, change7));
    Assertions.assertTrue(runtimeException.getMessage().contains("where data exists for Fileset"));

    createStorageLocation(new Path(storageLocation + "/subDir"));
    runtimeException =
        Assertions.assertThrowsExactly(
            RuntimeException.class, () -> alterFileset(filesetName, change9));
    Assertions.assertTrue(runtimeException.getMessage().contains("where data exists for Fileset"));
  }

  @Test
  void testNameSpec() {
    String illegalName = "/%~?*";

    NameIdentifier nameIdentifier =
        NameIdentifier.of(metalakeName, catalogName, schemaName, illegalName);

    Assertions.assertThrows(
        NoSuchFilesetException.class, () -> catalog.asFilesetCatalog().loadFileset(nameIdentifier));
  }

  @Test
  public void testLoadFileset() throws IOException {
    // create fileset
    String filesetName = "test_load_fileset";
    String storageLocation = storageLocation(filesetName);

    Fileset fileset =
        createFileset(
            filesetName,
            "comment",
            Fileset.Type.MANAGED,
            storageLocation,
            ImmutableMap.of("k1", "v1"));
    assertFilesetExists(filesetName);

    // test load fileset
    Fileset loadFileset =
        catalog
            .asFilesetCatalog()
            .loadFileset(NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName));
    Assertions.assertEquals(fileset.name(), loadFileset.name(), "fileset should be loaded");
    Assertions.assertEquals(fileset.comment(), loadFileset.comment(), "comment should be loaded");
    Assertions.assertEquals(fileset.type(), loadFileset.type(), "type should be loaded");
    Assertions.assertEquals(
        fileset.storageLocation(),
        loadFileset.storageLocation(),
        "storage location should be loaded");
    Assertions.assertEquals(
        fileset.properties(), loadFileset.properties(), "properties should be loaded");

    // test load a fileset that not exist
    Assertions.assertThrows(
        NoSuchFilesetException.class,
        () ->
            catalog
                .asFilesetCatalog()
                .loadFileset(NameIdentifier.of(metalakeName, catalogName, schemaName, "not_exist")),
        "Should throw NoSuchFilesetException when fileset does not exist");
  }

  @Test
  public void testDropManagedFileset() throws IOException {
    // create fileset
    String filesetName = "test_drop_managed_fileset";
    String storageLocation = storageLocation(filesetName);

    Assertions.assertFalse(
        hdfs.exists(new Path(storageLocation)), "storage location should not exists");

    createFileset(
        filesetName, "comment", Fileset.Type.MANAGED, storageLocation, ImmutableMap.of("k1", "v1"));
    assertFilesetExists(filesetName);

    // drop fileset
    boolean dropped =
        catalog
            .asFilesetCatalog()
            .dropFileset(NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName));
    Assertions.assertTrue(dropped, "fileset should be dropped");

    // verify fileset is dropped
    Assertions.assertFalse(
        catalog
            .asFilesetCatalog()
            .filesetExists(NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName)),
        "fileset should not be exists");
    Assertions.assertFalse(
        hdfs.exists(new Path(storageLocation)), "storage location should be dropped");
  }

  @Test
  public void testDropExternalFileset() throws IOException {
    // create fileset
    String filesetName = "test_drop_external_fileset";
    String storageLocation = storageLocation(filesetName);

    // internal version create EXTERNAL fileset need exist file dir
    Path filesetPath = new Path(storageLocation);
    FileSystem fs = filesetPath.getFileSystem(new Configuration());
    fs.mkdirs(filesetPath);

    createFileset(
        filesetName,
        "comment",
        Fileset.Type.EXTERNAL,
        storageLocation,
        ImmutableMap.of("k1", "v1"));
    assertFilesetExists(filesetName);

    // drop fileset
    boolean dropped =
        catalog
            .asFilesetCatalog()
            .dropFileset(NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName));
    Assertions.assertTrue(dropped, "fileset should be dropped");

    // verify fileset is dropped
    Assertions.assertFalse(
        catalog
            .asFilesetCatalog()
            .filesetExists(NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName)),
        "fileset should not be exists");
    Assertions.assertTrue(
        hdfs.exists(new Path(storageLocation)), "storage location should not be dropped");
  }

  @Test
  public void testListFilesets() throws IOException {
    // clear schema
    dropSchema();
    createSchema();

    // test no fileset exists
    NameIdentifier[] nameIdentifiers =
        catalog
            .asFilesetCatalog()
            .listFilesets(Namespace.ofFileset(metalakeName, catalogName, schemaName));
    Assertions.assertEquals(0, nameIdentifiers.length, "should have no fileset");

    // create fileset1
    String filesetName1 = "test_list_filesets1";
    String storageLocation = storageLocation(filesetName1);

    Fileset fileset1 =
        createFileset(
            filesetName1,
            "comment",
            Fileset.Type.MANAGED,
            storageLocation,
            ImmutableMap.of("k1", "v1"));
    assertFilesetExists(filesetName1);

    // create fileset2
    String filesetName2 = "test_list_filesets2";
    String storageLocation2 = storageLocation(filesetName2);

    Fileset fileset2 =
        createFileset(
            filesetName2,
            "comment",
            Fileset.Type.MANAGED,
            storageLocation2,
            ImmutableMap.of("k1", "v1"));
    assertFilesetExists(filesetName2);

    // list filesets
    NameIdentifier[] nameIdentifiers1 =
        catalog
            .asFilesetCatalog()
            .listFilesets(Namespace.ofFileset(metalakeName, catalogName, schemaName));
    Arrays.sort(nameIdentifiers1, Comparator.comparing(NameIdentifier::name));
    Assertions.assertEquals(2, nameIdentifiers1.length, "should have 2 filesets");
    Assertions.assertEquals(fileset1.name(), nameIdentifiers1[0].name());
    Assertions.assertEquals(fileset2.name(), nameIdentifiers1[1].name());
  }

  @Test
  public void testRenameFileset() throws IOException {
    // create fileset
    String filesetName = "test_rename_fileset";
    String storageLocation = storageLocation(filesetName);

    createFileset(
        filesetName, "comment", Fileset.Type.MANAGED, storageLocation, ImmutableMap.of("k1", "v1"));
    assertFilesetExists(filesetName);

    // rename fileset
    String newFilesetName = "test_rename_fileset_new";
    Fileset newFileset =
        catalog
            .asFilesetCatalog()
            .alterFileset(
                NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName),
                FilesetChange.rename(newFilesetName));

    // verify fileset is updated
    Assertions.assertNotNull(newFileset, "fileset should be created");
    Assertions.assertEquals(newFilesetName, newFileset.name(), "fileset name should be updated");
    Assertions.assertEquals("comment", newFileset.comment(), "comment should not be change");
    Assertions.assertEquals(Fileset.Type.MANAGED, newFileset.type(), "type should not be change");
    Assertions.assertEquals(
        storageLocation, newFileset.storageLocation(), "storage location should not be change");
    Assertions.assertEquals(5, newFileset.properties().size(), "properties should not be change");
    Assertions.assertEquals(
        "v1", newFileset.properties().get("k1"), "properties should not be change");
  }

  @Test
  public void testFilesetUpdateComment() throws IOException {
    // create fileset
    String filesetName = "test_update_fileset_comment";
    String storageLocation = storageLocation(filesetName);

    createFileset(
        filesetName, "comment", Fileset.Type.MANAGED, storageLocation, ImmutableMap.of("k1", "v1"));
    assertFilesetExists(filesetName);

    // update fileset comment
    String newComment = "new_comment";
    Fileset newFileset =
        catalog
            .asFilesetCatalog()
            .alterFileset(
                NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName),
                FilesetChange.updateComment(newComment));
    assertFilesetExists(filesetName);

    // verify fileset is updated
    Assertions.assertNotNull(newFileset, "fileset should be created");
    Assertions.assertEquals(newComment, newFileset.comment(), "comment should be updated");
    Assertions.assertEquals(Fileset.Type.MANAGED, newFileset.type(), "type should not be change");
    Assertions.assertEquals(
        storageLocation, newFileset.storageLocation(), "storage location should not be change");
    Assertions.assertEquals(5, newFileset.properties().size(), "properties should not be change");
    Assertions.assertEquals(
        "v1", newFileset.properties().get("k1"), "properties should not be change");
  }

  @Test
  public void testFilesetSetProperties() throws IOException {
    // create fileset
    String filesetName = "test_update_fileset_properties";
    String storageLocation = storageLocation(filesetName);

    createFileset(
        filesetName, "comment", Fileset.Type.MANAGED, storageLocation, ImmutableMap.of("k1", "v1"));
    assertFilesetExists(filesetName);

    // update fileset properties
    Fileset newFileset =
        catalog
            .asFilesetCatalog()
            .alterFileset(
                NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName),
                FilesetChange.setProperty("k1", "v2"));
    assertFilesetExists(filesetName);

    // verify fileset is updated
    Assertions.assertNotNull(newFileset, "fileset should be created");
    Assertions.assertEquals("comment", newFileset.comment(), "comment should not be change");
    Assertions.assertEquals(Fileset.Type.MANAGED, newFileset.type(), "type should not be change");
    Assertions.assertEquals(
        storageLocation, newFileset.storageLocation(), "storage location should not be change");
    Assertions.assertEquals(5, newFileset.properties().size(), "properties should not be change");
    Assertions.assertEquals(
        "v2", newFileset.properties().get("k1"), "properties should be updated");
  }

  @Test
  public void testFilesetRemoveProperties() throws IOException {
    // create fileset
    String filesetName = "test_remove_fileset_properties";
    String storageLocation = storageLocation(filesetName);

    createFileset(
        filesetName, "comment", Fileset.Type.MANAGED, storageLocation, ImmutableMap.of("k1", "v1"));
    assertFilesetExists(filesetName);

    // update fileset properties
    Fileset newFileset =
        catalog
            .asFilesetCatalog()
            .alterFileset(
                NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName),
                FilesetChange.removeProperty("k1"));
    assertFilesetExists(filesetName);

    // verify fileset is updated
    Assertions.assertNotNull(newFileset, "fileset should be created");
    Assertions.assertEquals("comment", newFileset.comment(), "comment should not be change");
    Assertions.assertEquals(Fileset.Type.MANAGED, newFileset.type(), "type should not be change");
    Assertions.assertEquals(
        storageLocation, newFileset.storageLocation(), "storage location should not be change");
    Assertions.assertEquals(4, newFileset.properties().size(), "properties should be removed");
  }

  @Test
  public void testFilesetRemoveComment() throws IOException {
    // create fileset
    String filesetName = "test_remove_fileset_comment";
    String storageLocation = storageLocation(filesetName);

    createFileset(
        filesetName, "comment", Fileset.Type.MANAGED, storageLocation, ImmutableMap.of("k1", "v1"));
    assertFilesetExists(filesetName);

    // remove fileset comment
    Fileset newFileset =
        catalog
            .asFilesetCatalog()
            .alterFileset(
                NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName),
                FilesetChange.removeComment());
    assertFilesetExists(filesetName);

    // verify fileset is updated
    Assertions.assertNotNull(newFileset, "fileset should be created");
    Assertions.assertNull(newFileset.comment(), "comment should be removed");
    Assertions.assertEquals(Fileset.Type.MANAGED, newFileset.type(), "type should not be changed");
    Assertions.assertEquals(
        storageLocation, newFileset.storageLocation(), "storage location should not be changed");
    Assertions.assertEquals(5, newFileset.properties().size(), "properties should not be changed");
    Assertions.assertEquals(
        "v1", newFileset.properties().get("k1"), "properties should not be changed");
  }

  @Test
  public void testDropCatalogWithEmptySchema() {
    String catalogName =
        GravitinoITUtils.genRandomName("test_drop_catalog_with_empty_schema_catalog");
    // Create a catalog without specifying location.
    Catalog filesetCatalog =
        metalake.createCatalog(
            NameIdentifier.of(metalakeName, catalogName),
            Catalog.Type.FILESET,
            provider,
            "comment",
            ImmutableMap.of());

    // Create a schema without specifying location.
    String schemaName =
        GravitinoITUtils.genRandomName("test_drop_catalog_with_empty_schema_schema");
    filesetCatalog
        .asSchemas()
        .createSchema(
            NameIdentifier.of(metalakeName, catalogName, schemaName), "comment", ImmutableMap.of());

    // Drop the empty schema.
    boolean dropped =
        filesetCatalog
            .asSchemas()
            .dropSchema(NameIdentifier.of(metalakeName, catalogName, schemaName), true);
    Assertions.assertTrue(dropped, "schema should be dropped");
    Assertions.assertFalse(
        filesetCatalog
            .asSchemas()
            .schemaExists(NameIdentifier.of(metalakeName, catalogName, schemaName)),
        "schema should not be exists");

    // Drop the catalog.
    dropped = metalake.dropCatalog(NameIdentifier.of(metalakeName, catalogName));
    Assertions.assertTrue(dropped, "catalog should be dropped");
    Assertions.assertFalse(
        metalake.catalogExists(NameIdentifier.of(metalakeName, catalogName)),
        "catalog should not be exists");
  }

  @Test
  public void testCreateMultipleDirectories() throws IOException {
    // test create a new directory when its parent dir does not exist.
    String catalogLocation = defaultBaseLocation + "/catalog1";
    String filesetLocation = defaultBaseLocation + "/catalog1/db1/fileset1";

    Path catalogPath = new Path(catalogLocation);
    Path filesetPath = new Path(filesetLocation);
    FileSystem fs = catalogPath.getFileSystem(new Configuration());
    Assertions.assertFalse(fs.exists(catalogPath));
    Assertions.assertTrue(fs.mkdirs(filesetPath));
    Assertions.assertTrue(fs.exists(filesetPath));
  }

  private Fileset createFileset(
      String filesetName,
      String comment,
      Fileset.Type type,
      String storageLocation,
      Map<String, String> properties) {
    if (storageLocation != null && type == Fileset.Type.MANAGED) {
      Path location = new Path(storageLocation);
      try {
        hdfs.deleteOnExit(location);
      } catch (IOException e) {
        LOG.warn("Failed to delete location: {}", location, e);
      }
    }

    return catalog
        .asFilesetCatalog()
        .createFileset(
            NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName),
            comment,
            type,
            storageLocation,
            properties);
  }

  private Fileset loadFileset(String filesetName) {
    return catalog
        .asFilesetCatalog()
        .loadFileset(NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName));
  }

  private boolean dropFileset(String filesetName) {
    return catalog
        .asFilesetCatalog()
        .dropFileset(NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName));
  }

  private Fileset alterFileset(String filesetName, FilesetChange... changes) {
    return catalog
        .asFilesetCatalog()
        .alterFileset(
            NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName), changes);
  }

  private void assertFilesetExists(String filesetName) throws IOException {
    Assertions.assertTrue(
        catalog
            .asFilesetCatalog()
            .filesetExists(NameIdentifier.of(metalakeName, catalogName, schemaName, filesetName)),
        "fileset should be exists");
    Assertions.assertTrue(
        hdfs.exists(new Path(storageLocation(filesetName))), "storage location should be exists");
  }

  private void assertLocationPermission(FileSystem hdfs, String storageLocation)
      throws IOException {
    AclStatus aclStatus = hdfs.getAclStatus(new Path(storageLocation));
    FsPermission permission = aclStatus.getPermission();
    List<AclEntry> entries = aclStatus.getEntries();

    Assertions.assertEquals(FsAction.NONE, permission.getOtherAction());
    Assertions.assertTrue(
        entries.contains(AclEntry.parseAclEntry("user:h_data_platform:rwx", true)));
    Assertions.assertTrue(
        entries.contains(AclEntry.parseAclEntry("default:user:h_data_platform:rwx", true)));
    Assertions.assertTrue(entries.contains(AclEntry.parseAclEntry("user:sql_prc:rwx", true)));
    Assertions.assertTrue(
        entries.contains(AclEntry.parseAclEntry("default:user:sql_prc:rwx", true)));
  }

  private static String defaultBaseLocation() {
    if (defaultBaseLocation == null) {
      defaultBaseLocation =
          String.format(
              "hdfs://%s:%d/user/hadoop/%s.db",
              containerSuite.getHiveContainer().getContainerIpAddress(),
              HiveContainer.HDFS_DEFAULTFS_PORT,
              schemaName.toLowerCase());
    }
    return defaultBaseLocation;
  }

  private static String storageLocation(String filesetName) {
    return defaultBaseLocation() + "/" + filesetName;
  }

  private static Stream<Arguments> filesetTypes() {
    return Stream.of(Arguments.of(Fileset.Type.EXTERNAL), Arguments.of(Fileset.Type.MANAGED));
  }

  private void createStorageLocation(Path storageLocationPath) {
    try {
      FileSystem fs = storageLocationPath.getFileSystem(new Configuration());
      fs.mkdirs(storageLocationPath);
    } catch (IOException e) {
      throw new RuntimeException(
          String.format("Failed to create storage location: %s", storageLocationPath.toString()),
          e);
    }
  }

  private boolean checkStorageLocationExists(Path storageLocationPath) {
    try {
      FileSystem fs = storageLocationPath.getFileSystem(new Configuration());
      return fs.exists(storageLocationPath);
    } catch (IOException e) {
      throw new RuntimeException(
          String.format("Failed to create storage location: %s", storageLocationPath.toString()),
          e);
    }
  }
}

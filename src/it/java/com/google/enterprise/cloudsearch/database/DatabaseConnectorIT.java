/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.enterprise.cloudsearch.database;

import static com.google.enterprise.cloudsearch.sdk.TestProperties.SERVICE_KEY_PROPERTY_NAME;
import static com.google.enterprise.cloudsearch.sdk.TestProperties.qualifyTestProperty;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.google.api.services.cloudsearch.v1.model.Item;
import com.google.api.services.cloudsearch.v1.model.ItemAcl;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.enterprise.cloudsearch.sdk.Util;
import com.google.enterprise.cloudsearch.sdk.config.Configuration.ResetConfigRule;
import com.google.enterprise.cloudsearch.sdk.indexing.Acl;
import com.google.enterprise.cloudsearch.sdk.indexing.CloudSearchService;
import com.google.enterprise.cloudsearch.sdk.indexing.DefaultAcl;
import com.google.enterprise.cloudsearch.sdk.indexing.DefaultAcl.DefaultAclMode;
import com.google.enterprise.cloudsearch.sdk.indexing.IndexingApplication;
import com.google.enterprise.cloudsearch.sdk.indexing.IndexingItemBuilder.ItemType;
import com.google.enterprise.cloudsearch.sdk.indexing.MockItem;
import com.google.enterprise.cloudsearch.sdk.indexing.StructuredDataHelper;
import com.google.enterprise.cloudsearch.sdk.indexing.TestUtils;
import com.google.enterprise.cloudsearch.sdk.indexing.template.FullTraversalConnector;
import com.google.enterprise.cloudsearch.sdk.sdk.ConnectorStats;
import com.google.enterprise.cloudsearch.sdk.serving.SearchHelper;
import com.google.enterprise.cloudsearch.sdk.serving.SearchTestUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.awaitility.Awaitility;
import org.awaitility.Duration;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests to check the integration between the database connector and CloudSearch Indexing API.
 */
@RunWith(JUnitParamsRunner.class)
public class DatabaseConnectorIT {
  private static final Logger logger = Logger.getLogger(DatabaseConnectorIT.class.getName());
  private static final String DATA_SOURCE_ID_PROPERTY_NAME = qualifyTestProperty("sourceId");
  private static final String ROOT_URL_PROPERTY_NAME = qualifyTestProperty("rootUrl");
  private static final String SQL_SERVER_PARAMS_PROPERTY_NAME =
      qualifyTestProperty("dbSqlParameters");
  private static final String APPLICATION_ID_PROPERTY_NAME =
      qualifyTestProperty("searchApplicationId");
  private static final String AUTH_INFO_USER1_PROPERTY_NAME =
      qualifyTestProperty("authInfoUser1");
  private static final String AUTH_INFO_USER2_PROPERTY_NAME =
      qualifyTestProperty("authInfoUser2");
  private static final Duration CONNECTOR_RUN_TIME = Duration.ONE_MINUTE;
  private static final Duration CONNECTOR_RUN_POLL_INTERVAL = Duration.FIVE_SECONDS;
  private static String dbSqlParameters;
  private static String keyFilePath;
  private static String indexingSourceId;
  private static Optional<String> rootUrl;
  private static CloudSearchService v1Client;
  private static TestUtils testUtils;
  private static SearchTestUtils searchUtil;
  private static SearchTestUtils searchUtilUser1;
  private static SearchTestUtils searchUtilUser2;
  private static String testUser1;
  private static String testUser2;

  private static class DatabaseConnectionParams {
    private final String dbUrl;
    private final String user;
    private final String password;

    private DatabaseConnectionParams(String dbUrl, String user, String password) {
      this.dbUrl = dbUrl;
      this.user = user;
      this.password = password;
    }
  }

  private static enum Database {
    H2, SQLSERVER, MYSQL
  };

  @Rule public ResetConfigRule resetConfig = new ResetConfigRule();
  @Rule public TemporaryFolder configFolder = new TemporaryFolder();
  @Rule public TestName name = new TestName();

  @BeforeClass
  public static void initialize() throws Exception {
    validateInputParams();
    v1Client = new CloudSearchService(keyFilePath, indexingSourceId, rootUrl);
    testUtils = new TestUtils(v1Client);
    String searchApplicationId = System.getProperty(APPLICATION_ID_PROPERTY_NAME);

    String[] authInfoUser1 = System.getProperty(AUTH_INFO_USER1_PROPERTY_NAME).split(",");
    SearchHelper searchHelperUser1 =
        SearchTestUtils.getSearchHelper(authInfoUser1, searchApplicationId, rootUrl);
    searchUtilUser1 = new SearchTestUtils(searchHelperUser1);
    testUser1 = authInfoUser1[0];

    String[] authInfoUser2 = System.getProperty(AUTH_INFO_USER2_PROPERTY_NAME).split(",");
    SearchHelper searchHelperUser2 =
        SearchTestUtils.getSearchHelper(authInfoUser2, searchApplicationId, rootUrl);
    searchUtilUser2 = new SearchTestUtils(searchHelperUser2);
    testUser2 = authInfoUser2[0];
  }

  private DatabaseConnectionParams getDatabaseConnectionParams(Database databaseType)
      throws IOException {
    if (databaseType == Database.SQLSERVER) {
      assumeThat("Invalid connection params for SQL Server. " + getUsageString(),
          dbSqlParameters, notNullValue());
      List<String> params =
          Splitter.on(",").trimResults().omitEmptyStrings().splitToList(dbSqlParameters);
      assumeThat("Invalid connection params for SQL Server. " + getUsageString(),
          params.size(), equalTo(3));
      return new DatabaseConnectionParams(params.get(0), params.get(1), params.get(2));
    } else if (databaseType == Database.MYSQL) {
      assumeThat("Invalid connection params for MYSQL Server. " + getUsageString(),
          dbSqlParameters, notNullValue());
      List<String> params =
          Splitter.on(",").trimResults().omitEmptyStrings().splitToList(dbSqlParameters);
      assumeThat("Invalid connection params for MYSQL Server. " + getUsageString(),
          params.size(), equalTo(3));
      return new DatabaseConnectionParams(params.get(0), params.get(1), params.get(2));
    } else {
      String dbUrl =
          "jdbc:h2:" + new File(configFolder.newFolder(), "integration-test").getAbsolutePath()
              + ";DATABASE_TO_UPPER=false";
      return new DatabaseConnectionParams(dbUrl, "sa", "");
    }
  }

  private static String getUsageString() {
    return "Missing input parameters. Rerun the test as \"mvn verify"
            + " -DargLine=-Dapi.test.serviceAccountPrivateKeyFile=./path/to/key.json"
            + " -Dapi.test.sourceId=dataSourceId\""
            + " -Dapi.test.dbSqlParameters="
            + "jdbc:sqlserver://<ipAddress>;databaseName=<dbName>;,<sqlUser>,<sqlPwd>\"";
  }

  private void executeDatabaseStatement(DatabaseConnectionParams dbConnection,
      List<String> queryStatement) throws SQLException, IOException {
    // h2 will automatically create database if not available.
    try (
        Connection connection =
        DriverManager.getConnection(dbConnection.dbUrl, dbConnection.user, dbConnection.password);
        Statement statement = connection.createStatement()) {
      for (String query : queryStatement) {
        statement.execute(query);
      }
    }
  }

  private Properties createRequiredProperties(DatabaseConnectionParams dbConnection)
      throws IOException {
    Properties config = new Properties();
    rootUrl.ifPresent(r -> config.setProperty("api.rootUrl", r));
    config.setProperty("api.sourceId", indexingSourceId);
    config.setProperty("api.serviceAccountPrivateKeyFile", keyFilePath);
    config.setProperty("connector.runOnce", "true");
    config.setProperty("connector.checkpointDirectory",
        configFolder.newFolder().getAbsolutePath());
    config.setProperty("db.url", dbConnection.dbUrl);
    config.setProperty("db.user", dbConnection.user);
    config.setProperty("db.password", dbConnection.password);
    config.setProperty("db.viewUrlColumns", "id");
    config.setProperty("db.uniqueKeyColumns", "id");
    config.setProperty("url.columns", "id, name");
    config.setProperty("itemMetadata.title.field", "id");
    config.setProperty("itemMetadata.contentLanguage.defaultValue", "en-US");
    config.setProperty("contentTemplate.db.title", "id");
    config.setProperty(DefaultAcl.DEFAULT_ACL_MODE, DefaultAclMode.FALLBACK.toString());
    config.setProperty(DefaultAcl.DEFAULT_ACL_PUBLIC, "true");
    config.setProperty("traverse.queueTag", "mockDatabaseConnectorQueue-" + Util.getRandomId());
    return config;
  }

  private static void validateInputParams() throws IOException, GeneralSecurityException {
    String dataSourceId;
    Path serviceKeyPath;
    logger.log(Level.FINE, "Validating input parameters...");
    try {
      dataSourceId = System.getProperty(DATA_SOURCE_ID_PROPERTY_NAME);
      serviceKeyPath = Paths.get(System.getProperty(SERVICE_KEY_PROPERTY_NAME));
      assertTrue(serviceKeyPath.toFile().exists());
      assertFalse(Strings.isNullOrEmpty(dataSourceId));
      rootUrl = Optional.ofNullable(System.getProperty(ROOT_URL_PROPERTY_NAME));
      dbSqlParameters = System.getProperty(SQL_SERVER_PARAMS_PROPERTY_NAME);
    } catch (AssertionError error) {
      logger.log(Level.SEVERE, getUsageString());
      throw error;
    }
    indexingSourceId = dataSourceId;
    keyFilePath = serviceKeyPath.toAbsolutePath().toString();
  }

  private String[] setupDataAndConfiguration(DatabaseConnectionParams dbConnection,
      Properties additionalConfig, List<String> queryStatement) throws SQLException, IOException {
    executeDatabaseStatement(dbConnection, queryStatement);
    Properties config = createRequiredProperties(dbConnection);
    config.putAll(additionalConfig);
    logger.log(Level.INFO, "Config file properties: {0}", config);
    File file = configFolder.newFile();
    try (FileOutputStream output = new FileOutputStream(file)) {
      config.store(output, "properties file");
      output.flush();
    }
    return new String[] {"-Dconfig=" + file.getAbsolutePath()};
  }

  @Test
  public void testHappyFlow() throws IOException, SQLException, InterruptedException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "Select id, name, phone from " + tableName);
    config.setProperty("db.allColumns", "id, name, phone");
    String row1 = "x1" + randomId;
    String row2 = "x2" + randomId;
    String row3 = "x3" + randomId;
    List<String> query = new ArrayList<>();
    List<String> mockItems = new ArrayList<>();
    query.add("create table " + tableName
        + "(id varchar(32) unique not null, name varchar(128), phone varchar(16))");
    query.add("insert into " + tableName + " (id, name, phone)"
        + " values ('" + row1 + "', 'Jones May', '2134')");
    query.add("insert into " + tableName + " (id, name, phone)"
        + " values ('" + row2 + "', 'Joe Smith', '9848')");
    query.add("insert into " + tableName + " (id, name, phone)"
        + " values ('" + row3 + "', 'Mike Brown', '3476')");
    try {
      DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.H2);
      String[] args = setupDataAndConfiguration(dbConnection, config, query);
      MockItem itemId1 = new MockItem.Builder(getItemId(row1))
          .setTitle(row1)
          .setSourceRepositoryUrl(row1)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .build();
      MockItem itemId2 = new MockItem.Builder(getItemId(row2))
          .setTitle(row2)
          .setSourceRepositoryUrl(row2)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .build();
      MockItem itemId3 = new MockItem.Builder(getItemId(row3))
          .setTitle(row3)
          .setSourceRepositoryUrl(row3)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .build();
      mockItems.addAll(asList(getItemId(row1), getItemId(row2), getItemId(row3)));
      runAwaitConnector(args);
      testUtils.waitUntilEqual(getItemId(row1), itemId1.getItem());
      testUtils.waitUntilEqual(getItemId(row2), itemId2.getItem());
      testUtils.waitUntilEqual(getItemId(row3), itemId3.getItem());
    } finally {
      v1Client.deleteItemsIfExist(mockItems);
    }
  }

  @Test
  public void testStructuredData() throws IOException, SQLException, InterruptedException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "Select id, textField as text, integerField as integer,"
        + " booleanField as boolean, doubleField as double, dateField as date,"
        + " timestampField as timestamp, enumField as enum from " + tableName);
    config.setProperty("db.allColumns", "id, textField, integerField, booleanField, doubleField,"
        + " dateField, timestampField, enumField");
    config.setProperty("itemMetadata.objectType.defaultValue", "myMockDataObject");
    config.setProperty("url.columns", "id");
    config.setProperty("url.format", "http://example.com/employee/{0}");
    String row1 = "s1" + randomId;
    String row2 = "s2" + randomId;
    String row3 = "s3" + randomId;

    List<String> query = new ArrayList<>();
    List<String> mockItems = new ArrayList<>();
    query.add("create table " + tableName + "(id varchar(32) unique not null,"
        + " textField varchar(128), integerField integer(50), booleanField boolean,"
        + " doubleField double, dateField date, timestampField timestamp, enumField integer)");
    query.add("insert into " + tableName
        + " (id, textField, integerField, booleanField, doubleField, dateField, timestampField,"
        + " enumField) values ('" + row1 + "', 'Jones May', '2134678', 'true', '2000', "
            + "'2007-11-20', '1907-10-10T14:21:23.400Z', '2')");
    query.add("insert into " + tableName
        + " (id, textField, integerField, booleanField, doubleField, dateField, timestampField,"
        + " enumField) values ('" + row2 + "', 'Joe Smith', '-9846', 'false', '12000.00',"
            + " '1987-02-28', '2017-10-10T14:01:23.400Z', '1')");
    query.add("insert into " + tableName
        + " (id, textField, integerField, booleanField, doubleField, dateField, timestampField,"
        + " enumField) values ('" + row3 + "', 'Mike Smith', '9358014', 'true', '-9000.00',"
            + " '1940-11-11', '1817-10-10T14:21:23.040Z', '2')");
    try {
      DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.H2);
      String[] args = setupDataAndConfiguration(dbConnection, config, query);

      MockItem itemId1 = new MockItem.Builder(getItemId(row1))
          .setTitle(row1)
          .setSourceRepositoryUrl("http://example.com/employee/" + row1)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .addValue("text", "Jones May")
          .addValue("integer", "2134678")
          .addValue("boolean", "true")
          .addValue("date", "2007-11-20")
          .addValue("double", "2000.00")
          .setObjectType("myMockDataObject")
          .addValue("timestamp", "1907-10-10T14:21:23.400Z")
          .addValue("enum", 2)
          .build();
      MockItem itemId2 = new MockItem.Builder(getItemId(row2))
          .setTitle(row2)
          .setSourceRepositoryUrl("http://example.com/employee/" + row2)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .addValue("text", "Joe Smith")
          .addValue("integer", "-9846")
          .addValue("boolean", "false")
          .addValue("date", "1987-02-28")
          .addValue("double", "12000.00")
          .setObjectType("myMockDataObject")
          .addValue("timestamp", "2017-10-10T14:01:23.400Z")
          .addValue("enum", 1)
          .build();
      MockItem itemId3 = new MockItem.Builder(getItemId(row3))
          .setTitle(row3)
          .setSourceRepositoryUrl("http://example.com/employee/" + row3)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .addValue("text", "Mike Smith")
          .addValue("integer", "9358014")
          .addValue("boolean", "true")
          .addValue("date", "1940-11-11")
          .addValue("double", "-9000.00")
          .setObjectType("myMockDataObject")
          .addValue("timestamp", "1817-10-10T14:21:23.040Z")
          .addValue("enum", 2)
          .build();
      mockItems.addAll(asList(getItemId(row1), getItemId(row2), getItemId(row3)));
      runAwaitConnector(args);
      verifyStructuredData(getItemId(row1), "myMockDataObject", itemId1.getItem());
      verifyStructuredData(getItemId(row2), "myMockDataObject", itemId2.getItem());
      verifyStructuredData(getItemId(row3), "myMockDataObject", itemId3.getItem());
    } finally {
      v1Client.deleteItemsIfExist(mockItems);
    }
  }

  @Test
  public void testItemMetadataDefaultValues()
      throws IOException, SQLException, InterruptedException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "Select id, title, name, phone, created, modified,"
        + " language from " + tableName);
    config.setProperty("db.allColumns", "id, title, name, phone, created, modified, language");
    config.setProperty("itemMetadata.title.field", "title");
    // itemMetadata.field.defaultValue will take effect in case of null values.
    config.setProperty("itemMetadata.title.defaultValue", "DEFAULT_TITLE");
    config.setProperty("url.format", "https://DEFAULT.URL/{0}");
    config.setProperty("itemMetadata.sourceRepositoryUrl.defaultValue",
        "https://example.org/" + "name");
    config.setProperty("itemMetadata.createTime.field", "created");
    config.setProperty("itemMetadata.createTime.defaultValue", "2007-10-10T14:21:23.400Z");
    config.setProperty("itemMetadata.updateTime.field", "modified");
    config.setProperty("itemMetadata.updateTime.defaultValue", "1919-10-10T14:21:23.400Z");
    config.setProperty("itemMetadata.contentLanguage.field", "language");
    config.setProperty("itemMetadata.contentLanguage.defaultValue", "fr-CA");
    String row1 = "row1" + randomId;
    String row2 = "row2" + randomId;
    List<String> query = new ArrayList<>();
    List<String> mockItems = new ArrayList<>();
    query.add("create table " + tableName + "(id varchar(32) unique not null, title varchar(100), "
        + " name varchar(128), phone integer, created timestamp, modified timestamp,"
        + " language varchar(10))");
    query.add("insert into " + tableName + " (id, title, name, phone, created, modified, language)"
        + " values ('" + row1 + "', 'TitleRow1', 'Jones May', '2134', '1907-10-10T14:21:23.400Z',"
            + " '2018-10-10T14:21:23.400Z', 'de-ch')");
    query.add("insert into " + tableName + " (id, name, phone)"
        + " values ('" + row2 + "', 'Mike Smith', '9848')");
    try {
      DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.H2);
      String[] args = setupDataAndConfiguration(dbConnection, config, query);
      MockItem itemId1 = new MockItem.Builder(getItemId(row1))
          .setTitle("TitleRow1")
          .setSourceRepositoryUrl("https://DEFAULT.URL/" + row1)
          .setContentLanguage("de-ch")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .setCreateTime("1907-10-10T14:21:23.400Z")
          .setUpdateTime("2018-10-10T14:21:23.400Z")
          .build();
      MockItem itemId2 = new MockItem.Builder(getItemId(row2))
          .setTitle("DEFAULT_TITLE")
          .setSourceRepositoryUrl("https://DEFAULT.URL/" + row2)
          .setContentLanguage("fr-CA")
          .setCreateTime("2007-10-10T14:21:23.400Z")
          .setUpdateTime("1919-10-10T14:21:23.400Z")
          .build();
      mockItems.addAll(asList(getItemId(row1), getItemId(row2)));
      runAwaitConnector(args);
      testUtils.waitUntilEqual(getItemId(row1), itemId1.getItem());
      testUtils.waitUntilEqual(getItemId(row2), itemId2.getItem());
    } finally {
      v1Client.deleteItemsIfExist(mockItems);
    }
  }

  @Test
  public void testArrayDataTypes() throws IOException, SQLException, InterruptedException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "Select id, arrayTextField as text,"
        + " arrayIntegerField as integer from " + tableName);
    config.setProperty("db.allColumns", "id, arrayTextField, arrayIntegerField");
    config.setProperty("itemMetadata.objectType.defaultValue", "myMockDataObject");
    config.setProperty("url.columns", "id");
    config.setProperty("url.format", "http://example.com/employee/{0}");
    String row1 = "row1" + randomId;
    String row2 = "row2" + randomId;

    List<String> query = new ArrayList<>();
    List<String> mockItems = new ArrayList<>();
    query.add("create table " + tableName + "(id varchar(32) unique not null,"
        + " arrayTextField array, arrayIntegerField array)");
    query.add("insert into " + tableName
        + " (id, arrayTextField, arrayIntegerField) values"
        + " ('" + row1 + "', ('joe', 'Smith', 'black'), ('1092', '8765'))");
    query.add("insert into " + tableName
        + " (id, arrayTextField, arrayIntegerField) values"
        + " ('" + row2 + "', ('Jim', 'Black'), ('1092873', '-128765'))");
    try {
      DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.H2);
      String[] args = setupDataAndConfiguration(dbConnection, config, query);
      MockItem itemId1 = new MockItem.Builder(getItemId(row1))
          .setTitle(row1)
          .setSourceRepositoryUrl("http://example.com/employee/" + row1)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .addValue("text", "joe")
          .addValue("text", "Smith")
          .addValue("text", "black")
          .addValue("integer", "1092")
          .addValue("integer", "8765")
          .setObjectType("myMockDataObject")
          .build();
     MockItem itemId2 = new MockItem.Builder(getItemId(row2))
          .setTitle(row2)
          .setSourceRepositoryUrl("http://example.com/employee/" + row2)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .addValue("text", "Jim")
          .addValue("text", "Black")
          .addValue("integer", "1092873")
          .addValue("integer", "-128765")
          .setObjectType("myMockDataObject")
          .build();
      mockItems.addAll(asList(getItemId(row1), getItemId(row2)));
      runAwaitConnector(args);
      verifyStructuredData(getItemId(row1), "myMockDataObject", itemId1.getItem());
      verifyStructuredData(getItemId(row2), "myMockDataObject", itemId2.getItem());
    } finally {
      v1Client.deleteItemsIfExist(mockItems);
    }
  }

  @Test
  public void blobDataType_succeeds()
      throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "select id, testcol from " + tableName);
    config.setProperty("db.allColumns", "id, testcol");
    config.setProperty("url.columns", "id");
    config.setProperty("url.format", "http://example.com/employee/{0}");
    config.setProperty("db.blobColumn", "testcol");
    config.setProperty("db.contentColumns", "*");
    String rowId = "row1" + randomId;

    String targetContent = "This is some blob content." + '\0' + '\t';
    DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.H2);
    try (Connection connection = DriverManager.getConnection(
        dbConnection.dbUrl, dbConnection.user, dbConnection.password)) {
      PreparedStatement stmt = connection.prepareStatement(
          "create table " + tableName + "(id varchar(32) unique not null, testcol blob)");
      stmt.execute();
      stmt.close();
      stmt = connection.prepareStatement(
          "insert into " + tableName + " (id, testcol) values ('" + rowId + "', ?)");
      Blob targetBlob = connection.createBlob();
      targetBlob.setBytes(1, targetContent.getBytes(UTF_8));
      stmt.setBlob(1, targetBlob);
      stmt.execute();
      stmt.close();
      targetBlob.free();
    }

    String[] args = setupDataAndConfiguration(dbConnection, config, new ArrayList<>());
    try {
      runAwaitConnector(args);
      Item item = v1Client.getItem(getItemId(rowId));
      assertEquals(targetContent, new String(item.getContent().decodeInlineContent()));
    } finally {
      executeDatabaseStatement(dbConnection, ImmutableList.of("drop table " + tableName));
      v1Client.deleteItemsIfExist(ImmutableList.of(getItemId(rowId)));
    }
  }

  @Test
  public void testSQLServer() throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "Select id, textField as text, intField as integer,"
        + " booleanField as boolean, floatField as float, dateField as date,"
        + " datetimeField as datetime, datetime2Field as datetime2, timeField as time,"
        + " smalldatetimeField as smalldatetime, datetimeoffsetField as datetimeoffset,"
        + " enumField as enum from " + tableName);
    config.setProperty("db.allColumns", "id, textField, intField, booleanField, floatField,"
        + " dateField, datetimeField, datetime2Field, timeField, smalldatetimeField,"
        + " datetimeoffsetField, enumField");
    config.setProperty("url.columns", "id");
    config.setProperty("url.format", "http://example.com/employee/{0}");
    config.setProperty("itemMetadata.objectType.defaultValue", "myMockDataObject");

    String row1 = "row1" + randomId;
    String row2 = "row2" + randomId;
    List<String> query = new ArrayList<>();
    List<String> mockItems = new ArrayList<>();
    query.add("create table " + tableName + " (id varchar(32) unique not null,"
        + " textField varchar(128), intField int, booleanField bit,"
        + " floatField float, dateField date, datetimeField datetime, datetime2Field datetime2,"
        + " timeField time, smalldatetimeField smalldatetime, datetimeoffsetField datetimeoffset,"
        + " enumField int)");
    query.add(
        "insert into " + tableName + " (id, textField, intField, booleanField, floatField,"
            + " dateField, datetimeField, datetime2Field, timeField, smalldatetimeField,"
            + " datetimeoffsetField, enumField)"
        + "values ('" + row1 + "', 'Jones May', '2134678', 'true', '2000.00', "
            + "'2007-11-20', '2007-11-20 10:10:20.123', '2007-11-20 10:10:20.1234567',"
            + " '10:10:20.1234567', '2007-11-20 10:10:20', '2007-11-20 10:10:20 +03:15', '2')");
    query.add(
        "insert into " + tableName + " (id, textField, intField, booleanField, floatField,"
            + " dateField, datetimeField, datetime2Field, timeField, smalldatetimeField,"
            + " datetimeoffsetField, enumField)"
        + "values ('" + row2 + "', 'Joe Smith', '-9846', 'false', '12000.00',"
          + "'1987-02-28', '1987-02-28 11:12:13.123', '1987-02-28 11:12:13.1234567',"
          + " '11:12:13.1234567', '1987-02-28 11:12:13', '1987-02-28 11:12:13 +05:15', '1')");
    DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.SQLSERVER);
    String[] args = setupDataAndConfiguration(dbConnection, config, query);
    MockItem itemId1 = new MockItem.Builder(getItemId(row1))
        .setTitle(row1)
        .setSourceRepositoryUrl("http://example.com/employee/" + row1)
        .setContentLanguage("en-US")
        .setItemType(ItemType.CONTENT_ITEM.toString())
        .addValue("text", "Jones May")
        .addValue("integer", "2134678")
        .addValue("boolean", "true")
        .addValue("float", "2000.00")
        .addValue("date", "2007-11-20")
        .addValue("datetime", "2007-11-20 10:10:20.123")
        .addValue("datetime2", "2007-11-20 10:10:20.1234567")
        .addValue("time", "10:10:20.1234567")
        .addValue("smalldatetime", "2007-11-20 10:10:20")
        .addValue("datetimeoffset", "2007-11-20 10:10:20 +03:15")
        .setObjectType("myMockDataObject")
        .addValue("enum", 2)
        .build();
    MockItem itemId2 = new MockItem.Builder(getItemId(row2))
        .setTitle(row2)
        .setSourceRepositoryUrl("http://example.com/employee/" + row2)
        .setContentLanguage("en-US")
        .setItemType(ItemType.CONTENT_ITEM.toString())
        .addValue("text", "Joe Smith")
        .addValue("integer", "-9846")
        .addValue("boolean", "false")
        .addValue("float", "12000.00")
        .addValue("date", "1987-02-28")
        .addValue("datetime", "1987-02-28 11:12:13.123")
        .addValue("datetime2", "1987-02-28 11:12:13.1234567")
        .addValue("time", "10:10:20.1234567")
        .addValue("smalldatetime", "1987-02-28 11:12:13")
        .addValue("datetimeoffset", "1987-02-28 11:12:13 +05:15")
        .setObjectType("myMockDataObject")
        .addValue("enum", 1)
        .build();
    mockItems.addAll(asList(getItemId(row1), getItemId(row2)));
    try {
      runAwaitConnector(args);
      verifyStructuredData(getItemId(row1), "myMockDataObject", itemId1.getItem());
      verifyStructuredData(getItemId(row2), "myMockDataObject", itemId2.getItem());
    } finally {
      executeDatabaseStatement(dbConnection, ImmutableList.of("drop table " + tableName));
      v1Client.deleteItemsIfExist(mockItems);
    }
  }

  @Test
  // "timestamp" type is not a JDBC timestamp, it's a binary type that maps to a
  // byte[]. You can't set it in an insert statement; the system handles that.
  public void sqlServer_timestamp_readAsArray_succeeds()
      throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "select id, testcol from " + tableName);
    config.setProperty("db.allColumns", "id, testcol");
    config.setProperty("url.columns", "id");
    config.setProperty("url.format", "http://example.com/employee/{0}");
    config.setProperty("itemMetadata.title.field", "testcol");

    String rowId = "row1" + randomId;
    List<String> query = new ArrayList<>();
    query.add("create table " + tableName
        + " (id varchar(32) unique not null, testcol timestamp)");
    query.add("insert into " + tableName + " (id) values ('" + rowId + "')");

    DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.SQLSERVER);
    String[] args = setupDataAndConfiguration(dbConnection, config, query);
    try {
      runAwaitConnector(args);
      Item item = v1Client.getItem(getItemId(rowId));
      assertEquals("Expected array for title",
          true, item.getMetadata().getTitle().startsWith("[B@"));
    } finally {
      executeDatabaseStatement(dbConnection, ImmutableList.of("drop table " + tableName));
      v1Client.deleteItemsIfExist(ImmutableList.of(getItemId(rowId)));
    }
  }

  @Test
  public void sqlServer_hierarchyid_readAsArray_succeeds()
      throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "select id, testcol from " + tableName);
    config.setProperty("db.allColumns", "id, testcol");
    config.setProperty("url.columns", "id");
    config.setProperty("url.format", "http://example.com/employee/{0}");
    config.setProperty("itemMetadata.title.field", "testcol");

    String rowId = "row1" + randomId;
    List<String> query = new ArrayList<>();
    query.add("create table " + tableName
        + " (id varchar(32) unique not null, testcol hierarchyid primary key clustered)");
    query.add("insert into " + tableName + " (id, testcol) values ('" + rowId + "', '/')");

    DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.SQLSERVER);
    String[] args = setupDataAndConfiguration(dbConnection, config, query);
    try {
      runAwaitConnector(args);
      Item item = v1Client.getItem(getItemId(rowId));
      assertEquals("Expected array for title",
          true, item.getMetadata().getTitle().startsWith("[B@"));
    } finally {
      executeDatabaseStatement(dbConnection, ImmutableList.of("drop table " + tableName));
      v1Client.deleteItemsIfExist(ImmutableList.of(getItemId(rowId)));
    }
  }

  @Test
  public void sqlServer_hierarchyid_readAsString_succeeds()
      throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql",
        "select id, testcol.ToString() as testcol from " + tableName);
    config.setProperty("db.allColumns", "id, testcol");
    config.setProperty("url.columns", "id");
    config.setProperty("url.format", "http://example.com/employee/{0}");
    config.setProperty("itemMetadata.title.field", "testcol");

    String rowId = "row1" + randomId;
    List<String> query = new ArrayList<>();
    query.add("create table " + tableName
        + " (id varchar(32) unique not null, testcol hierarchyid primary key clustered)");
    query.add("insert into " + tableName + " (id, testcol) values ('" + rowId + "', '/')");

    DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.SQLSERVER);
    String[] args = setupDataAndConfiguration(dbConnection, config, query);
    try {
      runAwaitConnector(args);
      Item item = v1Client.getItem(getItemId(rowId));
      assertEquals("/", item.getMetadata().getTitle());
    } finally {
      executeDatabaseStatement(dbConnection, ImmutableList.of("drop table " + tableName));
      v1Client.deleteItemsIfExist(ImmutableList.of(getItemId(rowId)));
    }
  }

  @Test
  public void sqlServer_uniqueidentifier_readAsString_succeeds()
      throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "select id, testcol from " + tableName);
    config.setProperty("db.allColumns", "id, testcol");
    config.setProperty("url.columns", "id");
    config.setProperty("url.format", "http://example.com/employee/{0}");
    config.setProperty("itemMetadata.title.field", "testcol");

    String rowId = "row1" + randomId;
    List<String> query = new ArrayList<>();
    query.add("create table " + tableName
        + " (id varchar(32) unique not null, testcol uniqueidentifier not null default newid())");
    query.add("insert into " + tableName + " (id, testcol) values ('" + rowId + "', newid())");

    DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.SQLSERVER);
    String[] args = setupDataAndConfiguration(dbConnection, config, query);
    try {
      runAwaitConnector(args);
      Item item = v1Client.getItem(getItemId(rowId));
      assertEquals(true, item.getMetadata().getTitle().matches(
              "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));
    } finally {
      executeDatabaseStatement(dbConnection, ImmutableList.of("drop table " + tableName));
      v1Client.deleteItemsIfExist(ImmutableList.of(getItemId(rowId)));
    }
  }

  @Test
  public void sqlServer_sql_variant_readAsString_succeeds()
      throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql",
        "select id, cast(testcol as varchar) as testcol from " + tableName);
    config.setProperty("db.allColumns", "id, testcol");
    config.setProperty("url.columns", "id");
    config.setProperty("url.format", "http://example.com/employee/{0}");
    config.setProperty("itemMetadata.title.field", "testcol");

    String rowId1 = "row1" + randomId;
    String rowId2 = "row2" + randomId;
    List<String> query = new ArrayList<>();
    query.add("create table " + tableName
        + " (id varchar(32) unique not null, testcol sql_variant)");
    query.add("insert into " + tableName + " (id, testcol) "
        + "values ('" + rowId1 + "', cast(11 as int))");
    query.add("insert into " + tableName + " (id, testcol) "
        + "values ('" + rowId2 + "', cast('variant data' as varchar(32)))");

    DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.SQLSERVER);
    String[] args = setupDataAndConfiguration(dbConnection, config, query);
    try {
      runAwaitConnector(args);
      Item item1 = v1Client.getItem(getItemId(rowId1));
      assertEquals("11", item1.getMetadata().getTitle());
      Item item2 = v1Client.getItem(getItemId(rowId2));
      assertEquals("variant data", item2.getMetadata().getTitle());
    } finally {
      executeDatabaseStatement(dbConnection, ImmutableList.of("drop table " + tableName));
      v1Client.deleteItemsIfExist(ImmutableList.of(getItemId(rowId1), getItemId(rowId2)));
    }
  }

  @Test
  public void sqlServer_xml_readAsString_succeeds()
      throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "select id, testcol from " + tableName);
    config.setProperty("db.allColumns", "id, testcol");
    config.setProperty("url.columns", "id");
    config.setProperty("url.format", "http://example.com/employee/{0}");
    config.setProperty("itemMetadata.title.field", "testcol");

    String rowId = "row1" + randomId;
    List<String> query = new ArrayList<>();
    query.add("create table " + tableName
        + " (id varchar(32) unique not null, testcol xml)");
    query.add("insert into " + tableName + " (id, testcol) values ('"
        + rowId + "', cast('<foo>data</foo>' as xml))");

    DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.SQLSERVER);
    String[] args = setupDataAndConfiguration(dbConnection, config, query);
    try {
      runAwaitConnector(args);
      Item item = v1Client.getItem(getItemId(rowId));
      assertEquals("<foo>data</foo>", item.getMetadata().getTitle());
    } finally {
      executeDatabaseStatement(dbConnection, ImmutableList.of("drop table " + tableName));
      v1Client.deleteItemsIfExist(ImmutableList.of(getItemId(rowId)));
    }
  }

  @Test
  public void sqlServer_geometry_readAsArray_succeeds()
      throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "select id, testcol from " + tableName);
    config.setProperty("db.allColumns", "id, testcol");
    config.setProperty("url.columns", "id");
    config.setProperty("url.format", "http://example.com/employee/{0}");
    config.setProperty("itemMetadata.title.field", "testcol");

    String rowId = "row1" + randomId;
    List<String> query = new ArrayList<>();
    query.add("create table " + tableName
        + " (id varchar(32) unique not null, testcol geometry)");
    query.add("insert into " + tableName + " (id, testcol) values ('"
        + rowId + "', geometry::STGeomFromText('LINESTRING (100 100, 20 180, 180 180)', 0))");

    DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.SQLSERVER);
    String[] args = setupDataAndConfiguration(dbConnection, config, query);
    try {
      runAwaitConnector(args);
      Item item = v1Client.getItem(getItemId(rowId));
      assertEquals("Expected array for title",
          true, item.getMetadata().getTitle().startsWith("[B@"));
    } finally {
      executeDatabaseStatement(dbConnection, ImmutableList.of("drop table " + tableName));
      v1Client.deleteItemsIfExist(ImmutableList.of(getItemId(rowId)));
    }
  }

  @Test
  public void sqlServer_geometry_readAsString_succeeds()
      throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql",
        "select id, testcol.STAsText() as testcol from " + tableName);
    config.setProperty("db.allColumns", "id, testcol");
    config.setProperty("url.columns", "id");
    config.setProperty("url.format", "http://example.com/employee/{0}");
    config.setProperty("itemMetadata.title.field", "testcol");

    String rowId = "row1" + randomId;
    List<String> query = new ArrayList<>();
    query.add("create table " + tableName
        + " (id varchar(32) unique not null, testcol geometry)");
    query.add("insert into " + tableName + " (id, testcol) values ('"
        + rowId + "', geometry::STGeomFromText('LINESTRING (100 100, 20 180, 180 180)', 0))");

    DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.SQLSERVER);
    String[] args = setupDataAndConfiguration(dbConnection, config, query);
    try {
      runAwaitConnector(args);
      Item item = v1Client.getItem(getItemId(rowId));
      assertEquals("LINESTRING (100 100, 20 180, 180 180)", item.getMetadata().getTitle());
    } finally {
      executeDatabaseStatement(dbConnection, ImmutableList.of("drop table " + tableName));
      v1Client.deleteItemsIfExist(ImmutableList.of(getItemId(rowId)));
    }
  }

  @Test
  public void sqlServer_geography_readAsArray_succeeds()
      throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "select id, testcol from " + tableName);
    config.setProperty("db.allColumns", "id, testcol");
    config.setProperty("url.columns", "id");
    config.setProperty("url.format", "http://example.com/employee/{0}");
    config.setProperty("itemMetadata.title.field", "testcol");

    String rowId = "row1" + randomId;
    List<String> query = new ArrayList<>();
    query.add("create table " + tableName
        + " (id varchar(32) unique not null, testcol geography)");
    query.add("insert into " + tableName + " (id, testcol) values ('" + rowId
        + "', geography::STGeomFromText('LINESTRING(-122.360 47.656, -122.343 47.656 )', 4326))");

    DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.SQLSERVER);
    String[] args = setupDataAndConfiguration(dbConnection, config, query);
    try {
      runAwaitConnector(args);
      Item item = v1Client.getItem(getItemId(rowId));
      assertEquals("Expected array for title",
          true, item.getMetadata().getTitle().startsWith("[B@"));
    } finally {
      executeDatabaseStatement(dbConnection, ImmutableList.of("drop table " + tableName));
      v1Client.deleteItemsIfExist(ImmutableList.of(getItemId(rowId)));
    }
  }

  @Test
  public void sqlServer_geography_readAsString_succeeds()
      throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql",
        "select id, testcol.STAsText() as testcol from " + tableName);
    config.setProperty("db.allColumns", "id, testcol");
    config.setProperty("url.columns", "id");
    config.setProperty("url.format", "http://example.com/employee/{0}");
    config.setProperty("itemMetadata.title.field", "testcol");

    String rowId = "row1" + randomId;
    List<String> query = new ArrayList<>();
    query.add("create table " + tableName
        + " (id varchar(32) unique not null, testcol geography)");
    query.add("insert into " + tableName + " (id, testcol) values ('" + rowId
        + "', geography::STGeomFromText('LINESTRING(-122.360 47.656, -122.343 47.656)', 4326))");

    DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.SQLSERVER);
    String[] args = setupDataAndConfiguration(dbConnection, config, query);
    try {
      runAwaitConnector(args);
      Item item = v1Client.getItem(getItemId(rowId));
      assertEquals("LINESTRING (-122.36 47.656, -122.343 47.656)", item.getMetadata().getTitle());
    } finally {
      executeDatabaseStatement(dbConnection, ImmutableList.of("drop table " + tableName));
      v1Client.deleteItemsIfExist(ImmutableList.of(getItemId(rowId)));
    }
  }

  @Test
  @Parameters({
    "char(9), test data",
    "varchar(32), testing data",
    "text, test data",
    "nchar(11), test%20data",
    "nvarchar(128), test%20data",
    "ntext, test%20data"
  })
  public void stringDataTypes_inSQLServer_succeeds(String type, String data)
      throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = "stringDataTypes_inSQLServer_succeeds" + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "Select id, text from " + tableName);
    config.setProperty("db.allColumns", "id, text");
    config.setProperty("url.columns", "id");
    config.setProperty("url.format", "http://example.com/employee/{0}");
    config.setProperty("itemMetadata.objectType.defaultValue", "myMockDataObject");

    String row = "row" + randomId;
    List<String> query = new ArrayList<>();
    List<String> mockItems = new ArrayList<>();

    String createSQL = String.format(
        "create table %s (id varchar(32) unique not null, text %s)",
        tableName, type);
    String insertSQL = String.format(
        "insert into %s (id, text) values ('%s', '%s')",
        tableName, row, data);
    query.add(createSQL);
    query.add(insertSQL);

    DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.SQLSERVER);
    String[] args = setupDataAndConfiguration(dbConnection, config, query);
    MockItem itemId1 = new MockItem.Builder(getItemId(row))
        .setTitle(row)
        .setSourceRepositoryUrl("http://example.com/employee/" + row)
        .setContentLanguage("en-US")
        .setItemType(ItemType.CONTENT_ITEM.toString())
        .addValue("text", data)
        .setObjectType("myMockDataObject")
        .build();
    mockItems.addAll(asList(getItemId(row)));

    try {
      runAwaitConnector(args);
      testUtils.waitUntilEqual(getItemId(row), itemId1.getItem());
      verifyStructuredData(getItemId(row), "myMockDataObject", itemId1.getItem());
    } finally {
      executeDatabaseStatement(dbConnection, ImmutableList.of("drop table " + tableName));
      v1Client.deleteItemsIfExist(mockItems);
    }
  }

  @Test
  public void testFullTraversalWithUpdates()
      throws IOException, SQLException, InterruptedException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "Select id, name, phone from " + tableName);
    config.setProperty("db.allRecordsSql", "Select id, name, phone from " + tableName);
    config.setProperty("db.allColumns", "id, name, phone");
    config.setProperty("connector.runOnce", "false");
    config.setProperty("schedule.traversalIntervalSecs", "10");

    String row1 = "row1" + randomId;
    String row2 = "row2" + randomId;
    String row3 = "row3" + randomId;
    String row4 = "row4" + randomId;
    List<String> mockItems = new ArrayList<>();
    List<String> query = ImmutableList.of("create table " + tableName
        + "(id varchar(32) unique not null, name varchar(128), phone varchar(16))",
        " insert into " + tableName + " (id, name, phone)"
        + " values ('" + row1 + "', 'Jones May', '2134')",
        " insert into " + tableName + " (id, name, phone)"
        + " values ('" + row2 + "', 'Joe Smith', '9848')",
        " insert into " + tableName + " (id, name, phone)"
        + " values ('" + row3 + "', 'Mike Brown', '3476')");
    try {
      DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.H2);
      String[] args = setupDataAndConfiguration(dbConnection, config, query);
      MockItem itemId1 = new MockItem.Builder(getItemId(row1))
          .setTitle(row1)
          .setSourceRepositoryUrl(row1)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .build();
     MockItem itemId2 = new MockItem.Builder(getItemId(row2))
          .setTitle(row2)
          .setSourceRepositoryUrl(row2)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .build();
     MockItem itemId3 = new MockItem.Builder(getItemId(row3))
          .setTitle(row3)
          .setSourceRepositoryUrl(row3)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .build();
      mockItems = ImmutableList.of(getItemId(row1), getItemId(row2), getItemId(row3));
      IndexingApplication dbConnector = runConnector(args);
      Awaitility.await()
          .atMost(CONNECTOR_RUN_TIME)
          .pollInterval(CONNECTOR_RUN_POLL_INTERVAL)
          .until(() -> ConnectorStats.getSuccessfulFullTraversalsCount() > 0);
      testUtils.waitUntilEqual(getItemId(row1), itemId1.getItem());
      testUtils.waitUntilEqual(getItemId(row2), itemId2.getItem());
      testUtils.waitUntilEqual(getItemId(row3), itemId3.getItem());
      query = ImmutableList.of("update " + tableName
          + " set name = 'Jones Mary', phone = '21345' where id = '" + row1 + "'",
          " delete from " + tableName + " where id = '" + row2 + "'",
          " insert into " + tableName + " (id, name, phone)"
          + " values ('" + row4 + "', 'Aleks Smith', '0984')");
      executeDatabaseStatement(dbConnection, query);
      int traversalCount = ConnectorStats.getSuccessfulFullTraversalsCount();
      // Wait for 2 successful full traversal run, in order to ensure connector completes
      // its full traversal after data update.
      Awaitility.await()
          .atMost(CONNECTOR_RUN_TIME)
          .pollInterval(CONNECTOR_RUN_POLL_INTERVAL)
          .until(() -> ConnectorStats.getSuccessfulFullTraversalsCount() > traversalCount + 2);
      dbConnector.shutdown("Shutdown Initiated");
      MockItem updateItemId = new MockItem.Builder(getItemId(row1))
          .setTitle(row1)
          .setSourceRepositoryUrl(row1)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .build();
      MockItem newItemId = new MockItem.Builder(getItemId(row4))
          .setTitle(row4)
          .setSourceRepositoryUrl(row4)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .build();
      mockItems = ImmutableList.of(getItemId(row1), getItemId(row3), getItemId(row4));
      testUtils.waitUntilEqual(getItemId(row1), updateItemId.getItem());
      testUtils.waitUntilDeleted(getItemId(row2));
      testUtils.waitUntilEqual(getItemId(row3), itemId3.getItem());
      testUtils.waitUntilEqual(getItemId(row4), newItemId.getItem());
    } finally {
      v1Client.deleteItemsIfExist(mockItems);
    }
  }

  @Test
  public void incrementalTraversal_updateExistingRecord()
      throws IOException, SQLException, InterruptedException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "Select id, name, phone, dateField,"
        + " integerField from " + tableName);
    config.setProperty("db.allRecordsSql", "Select id, name, phone, dateField,"
        + " integerField from " + tableName);
    config.setProperty("db.allColumns", "id, name, phone, date");
    config.setProperty("connector.runOnce", "false");
    config.setProperty("schedule.incrementalTraversalIntervalSecs", "10");
    config.setProperty("db.incrementalUpdateSql", "select id, name, phone, dateField,"
        + " integerField from " + tableName + " where dateField > ?");
    String row1 = "row1" + randomId;
    String row2 = "row2" + randomId;
    List<String> mockItems = new ArrayList<>();
    List<String> query = ImmutableList.of("create table " + tableName
        + "(id varchar(32) unique not null, name varchar(128), phone varchar(16),"
        + " dateField timestamp, integerField integer)",
        "insert into " + tableName + " (id, name, phone, dateField, integerField)"
        + " values ('" + row1 + "', 'Jones May', '2134', '1907-10-10T14:21:23.400Z', '1')",
        "insert into " + tableName + " (id, name, phone)"
        + " values ('" + row2 + "', 'Joe Smith', '9848')");
    try {
      DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.H2);
      String[] args = setupDataAndConfiguration(dbConnection, config, query);
      MockItem itemId1 = new MockItem.Builder(getItemId(row1))
          .setTitle(row1)
          .setSourceRepositoryUrl(row1)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .build();
     MockItem itemId2 = new MockItem.Builder(getItemId(row2))
          .setTitle(row2)
          .setSourceRepositoryUrl(row2)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .build();
      mockItems = ImmutableList.of(getItemId(row1), getItemId(row2));
      IndexingApplication dbConnector = runConnector(args);
      Awaitility.await()
          .atMost(CONNECTOR_RUN_TIME)
          .pollInterval(CONNECTOR_RUN_POLL_INTERVAL)
          .until(() -> ConnectorStats.getSuccessfulFullTraversalsCount() > 0);
      testUtils.waitUntilEqual(getItemId(row1), itemId1.getItem());
      testUtils.waitUntilEqual(getItemId(row2), itemId2.getItem());
      executeDatabaseStatement(dbConnection,
          Collections.singletonList("update " + tableName
              + " set name = 'Jones Mary', phone = '21345',"
              + " dateField = '2019-01-24T14:21:23.400Z', integerField = '2' where id = '"
              + row1 + "'"));
      int traversalCount = ConnectorStats.getSuccessfulIncrementalTraversalsCount();
      Awaitility.await()
          .atMost(CONNECTOR_RUN_TIME)
          .pollInterval(CONNECTOR_RUN_POLL_INTERVAL)
          .until(() -> ConnectorStats.
              getSuccessfulIncrementalTraversalsCount() > traversalCount + 2);
      dbConnector.shutdown("Shutdown Initiated");
      MockItem updateItemId = new MockItem.Builder(getItemId(row1))
          .setTitle(row1)
          .setSourceRepositoryUrl(row1)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .build();
      mockItems = ImmutableList.of(getItemId(row1), getItemId(row2));
      testUtils.waitUntilEqual(getItemId(row1), updateItemId.getItem());
      testUtils.waitUntilEqual(getItemId(row2), itemId2.getItem());
    } finally {
      v1Client.deleteItemsIfExist(mockItems);
    }
  }

  @Test
  public void incrementalTraversal_addNewRecord()
      throws IOException, SQLException, InterruptedException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "Select id, name, phone, dateField,"
        + " integerField from " + tableName);
    config.setProperty("db.allRecordsSql", "Select id, name, phone, dateField,"
        + " integerField from " + tableName);
    config.setProperty("db.allColumns", "id, name, phone, date");
    config.setProperty("connector.runOnce", "false");
    config.setProperty("schedule.incrementalTraversalIntervalSecs", "10");
    config.setProperty("db.incrementalUpdateSql", "select id, name, phone, dateField,"
        + " integerField from " + tableName + " where dateField > ?");
    String row1 = "row1" + randomId;
    String row2 = "row2" + randomId;
    List<String> mockItems = new ArrayList<>();
    List<String> query = ImmutableList.of("create table " + tableName
        + "(id varchar(32) unique not null, name varchar(128), phone varchar(16),"
        + " dateField datetime, integerField integer)",
        "insert into " + tableName + " (id, name, phone, dateField, integerField)"
        + " values ('" + row1 + "', 'Jones May', '2134', '2009-01-24T04:21:23.400Z' , '1')");
    try {
      DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.H2);
      String[] args = setupDataAndConfiguration(dbConnection, config, query);
      MockItem itemId1 = new MockItem.Builder(getItemId(row1))
          .setTitle(row1)
          .setSourceRepositoryUrl(row1)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .build();
      mockItems = ImmutableList.of(getItemId(row1));
      IndexingApplication dbConnector = runConnector(args);
      Awaitility.await()
          .atMost(CONNECTOR_RUN_TIME)
          .pollInterval(CONNECTOR_RUN_POLL_INTERVAL)
          .until(() -> ConnectorStats.getSuccessfulFullTraversalsCount() > 0);
      testUtils.waitUntilEqual(getItemId(row1), itemId1.getItem());
      executeDatabaseStatement(
          dbConnection,
          Collections.singletonList("insert into " + tableName + " (id, name, phone, dateField)"
              + " values ('" + row2 + "', 'Aleks Smith', '0984', CURRENT_TIMESTAMP())"));
      int traversalCount = ConnectorStats.getSuccessfulIncrementalTraversalsCount();
      // Wait for 2 successful incremental traversal run, to ensure connector completes
      // its incremental traversal after data update.
      Awaitility.await()
          .atMost(CONNECTOR_RUN_TIME)
          .pollInterval(CONNECTOR_RUN_POLL_INTERVAL)
          .until(() -> ConnectorStats
              .getSuccessfulIncrementalTraversalsCount() > traversalCount + 2);
      dbConnector.shutdown("Shutdown Initiated");
      MockItem newItemId = new MockItem.Builder(getItemId(row2))
          .setTitle(row2)
          .setSourceRepositoryUrl(row2)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .build();
      mockItems = ImmutableList.of(getItemId(row1), getItemId(row2));
      testUtils.waitUntilEqual(getItemId(row1), itemId1.getItem());
      testUtils.waitUntilEqual(getItemId(row2), newItemId.getItem());
      } finally {
      v1Client.deleteItemsIfExist(mockItems);
    }
  }

  @Test
  public void defaultAclMode_fallback_verifyServing()
      throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    String row1 = "row1" + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql",
        "Select id, textField as text, integerField as integer, booleanField as boolean,"
        + " doubleField as double, timestampField as timestamp from " + tableName);
    config.setProperty("db.allColumns",
        "id, textField, integerField, booleanField, doubleField, timestampField");
    config.setProperty("itemMetadata.objectType.defaultValue", "myMockDataObject");
    config.setProperty("url.columns", "id");
    config.setProperty("url.format", "http://example.com/employee/{0}");
    config.setProperty(DefaultAcl.DEFAULT_ACL_READERS_USERS, "google:" + testUser1);
    config.setProperty(DefaultAcl.DEFAULT_ACL_PUBLIC, "false");
    config.setProperty(DefaultAcl.DEFAULT_ACL_MODE, DefaultAclMode.FALLBACK.toString());
    config.setProperty(DefaultAcl.DEFAULT_ACL_NAME, "mockdb_fallbackAcl_" + Util.getRandomId());

    List<String> query = ImmutableList.of(
        "create table " + tableName + "(id varchar(50) unique not null,"
        + " textField varchar(128), integerField integer(50), booleanField boolean,"
        + " doubleField double, timestampField timestamp)",
        "insert into " + tableName
        + " (id, textField, integerField, booleanField, doubleField, timestampField) values ('"
        + row1 + "', 'Jones May', '2134678', 'true', '2000', '1907-10-10T14:21:23.400Z')");
    try {
      DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.H2);
      String[] args = setupDataAndConfiguration(dbConnection, config, query);
      runAwaitConnector(args);
      MockItem itemId1 = new MockItem.Builder(getItemId(row1))
          .setTitle(row1)
          .setSourceRepositoryUrl("http://example.com/employee/" + row1)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .addValue("text", "Jones May")
          .addValue("integer", "2134678")
          .addValue("boolean", "true")
          .addValue("timestamp", "1907-10-10T14:21:23.400Z")
          .addValue("double", "2000.00")
          .setObjectType("myMockDataObject")
          .build();
      verifyStructuredData(getItemId(row1), "myMockDataObject", itemId1.getItem());
      searchUtilUser1.waitUntilItemServed(row1, row1);
    } finally {
      v1Client.deleteItemsIfExist(ImmutableList.of(getItemId(row1)));
    }
  }

  @Test
  public void defaultAclMode_append_verifyServing()
      throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    String row1 = "row1" + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "select id, textField as text, "
        + "readers_users from " + tableName);
    config.setProperty("db.allColumns", "id, textField, readers_users");
    config.setProperty("url.columns", "id");
    config.setProperty("url.format", "http://example.com/employee/{0}");
    config.setProperty(DefaultAcl.DEFAULT_ACL_READERS_USERS, "google:" + testUser1);
    config.setProperty(DefaultAcl.DEFAULT_ACL_PUBLIC, "false");
    config.setProperty(DefaultAcl.DEFAULT_ACL_MODE, DefaultAclMode.APPEND.toString());
    config.setProperty(DefaultAcl.DEFAULT_ACL_NAME, "mockdb_appendAcl_" + Util.getRandomId());

    List<String> query = ImmutableList.of(
        "create table " + tableName + "(id varchar(50) unique not null, textField varchar(128), "
            + "readers_users varchar(128))",
        "insert into " + tableName + " (id, textField, readers_users) "
            + "values ('" + row1 + "', 'Jones May', " + "'google:" + testUser2 + "'" + ")"
        );

    try {
      DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.H2);
      String[] args = setupDataAndConfiguration(dbConnection, config, query);
      runAwaitConnector(args);

      ItemAcl expectedAcl =
          new ItemAcl()
              .setReaders(
                  Arrays.asList(
                      Acl.getGoogleUserPrincipal(testUser2),
                      Acl.getGoogleUserPrincipal(testUser1)));
      assertEquals(expectedAcl, v1Client.getItem(getItemId(row1)).getAcl());
      searchUtilUser1.waitUntilItemServed(row1, row1);
      searchUtilUser2.waitUntilItemServed(row1, row1);
    } finally {
      v1Client.deleteItemsIfExist(ImmutableList.of(getItemId(row1)));
    }
  }

  @Test
  public void defaultAclMode_override_verifyServing()
      throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    String row1 = "row1" + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "Select id, textField as text from " + tableName);
    config.setProperty("db.allColumns", "id, textField");
    config.setProperty("itemMetadata.objectType.defaultValue", "myMockDataObject");
    config.setProperty("url.columns", "id");
    config.setProperty("url.format", "http://example.com/employee/{0}");

    config.setProperty(DefaultAcl.DEFAULT_ACL_READERS_USERS, "google:" + testUser1);
    config.setProperty(DefaultAcl.DEFAULT_ACL_PUBLIC, "false");
    config.setProperty(DefaultAcl.DEFAULT_ACL_MODE, DefaultAclMode.OVERRIDE.toString());
    config.setProperty(DefaultAcl.DEFAULT_ACL_NAME, "mockdb_overrideAcl_" + Util.getRandomId());
    Acl acl = new Acl.Builder()
        .setReaders(Collections.singletonList(Acl.getGoogleUserPrincipal(testUser2)))
        .build();

    List<String> query = ImmutableList.of(
        "create table " + tableName + "(id varchar(50) unique not null, textField varchar(128))",
        "insert into " + tableName + " (id, textField) values ('" + row1 + "', 'Jones May')"
        );

    try {
      DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.H2);
      String[] args = setupDataAndConfiguration(dbConnection, config, query);
      runAwaitConnector(args);
      MockItem itemId1 = new MockItem.Builder(getItemId(row1))
          .setTitle(row1)
          .setSourceRepositoryUrl("http://example.com/employee/" + row1)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .addValue("text", "Jones May")
          .setObjectType("myMockDataObject")
          .setAcl(acl)
          .build();
      verifyStructuredData(getItemId(row1), "myMockDataObject", itemId1.getItem());
      searchUtilUser1.waitUntilItemServed(row1, row1);
      searchUtilUser2.waitUntilItemNotServed(row1, row1);
    } finally {
      v1Client.deleteItemsIfExist(ImmutableList.of(getItemId(row1)));
    }
  }

  @Test
  public void incrementalTraversal_addAcl_succeeds()
      throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "Select id, name, dateField,"
        + " readers_users from " + tableName);
    config.setProperty("db.allColumns", "id, name, dateField, readers_users");
    config.setProperty("connector.runOnce", "false");
    config.setProperty("defaultAcl.mode", DefaultAclMode.FALLBACK.toString());
    config.setProperty("defaultAcl.public", "false");
    config.setProperty("defaultAcl.readers.users", "google:" + testUser1);
    config.setProperty("schedule.incrementalTraversalIntervalSecs", "10");
    config.setProperty("db.incrementalUpdateSql", "select id, name, dateField,"
        + " readers_users from " + tableName + " where dateField > ?");
    String row = "row" + randomId;
    String createSQL = String.format(
        "create table %s (id varchar(32) unique not null, "
            + "name varchar(128),  dateField timestamp, readers_users varchar(128))",
        tableName);
    String insertSQL = String.format(
        "insert into %s (id, name,  dateField) "
            + "values ('%s', '%s', '%s')",
        tableName, row, "Jones May", "1907-10-10T14:21:23.400Z");

    try {
      DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.H2);
      List<String> query = ImmutableList.of(createSQL, insertSQL);
      String[] args = setupDataAndConfiguration(dbConnection, config, query);
      MockItem item = new MockItem.Builder(getItemId(row))
          .setTitle(row)
          .setSourceRepositoryUrl(row)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .build();
      IndexingApplication dbConnector = runConnector(args);
      Awaitility.await()
          .atMost(CONNECTOR_RUN_TIME)
          .pollInterval(CONNECTOR_RUN_POLL_INTERVAL)
          .until(() -> ConnectorStats.getSuccessfulFullTraversalsCount() > 0);
      testUtils.waitUntilEqual(getItemId(row), item.getItem());
      ItemAcl expectedAcl =
          new ItemAcl()
              .setReaders(
                  Arrays.asList(
                      Acl.getGoogleUserPrincipal(testUser1)));
      searchUtilUser1.waitUntilItemServed(row, row);

      String updateSQL =
          String.format(
              "update %s set dateField = CURRENT_TIMESTAMP(), readers_users = "
                  + "'google:%s' where id = '%s'",
              tableName, testUser2, row);
      executeDatabaseStatement(dbConnection,
          Collections.singletonList(updateSQL));
      int traversalCount = ConnectorStats.getSuccessfulIncrementalTraversalsCount();
      Awaitility.await()
          .atMost(CONNECTOR_RUN_TIME)
          .pollInterval(CONNECTOR_RUN_POLL_INTERVAL)
          .until(() -> ConnectorStats.
              getSuccessfulIncrementalTraversalsCount() > traversalCount + 1);
      dbConnector.shutdown("Shutdown Initiated");
      MockItem updatedItem = new MockItem.Builder(getItemId(row))
          .setTitle(row)
          .setSourceRepositoryUrl(row)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .build();
      testUtils.waitUntilEqual(getItemId(row), updatedItem.getItem());
      expectedAcl =
          new ItemAcl()
              .setReaders(
                  Arrays.asList(
                      Acl.getGoogleUserPrincipal(testUser2)));
      assertEquals(expectedAcl, v1Client.getItem(getItemId(row)).getAcl());
      searchUtilUser1.waitUntilItemNotServed(row, row);
      searchUtilUser2.waitUntilItemServed(row, row);
    } finally {
      v1Client.deleteItemsIfExist(ImmutableList.of(getItemId(row)));
    }
  }

  @Test
  public void incrementalTraversal_updateAcl_succeeds()
      throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "Select id, name, dateField,"
        + " readers_users from " + tableName);
    config.setProperty("db.allColumns", "id, name, dateField, readers_users");
    config.setProperty("connector.runOnce", "false");
    config.setProperty("schedule.incrementalTraversalIntervalSecs", "10");
    config.setProperty("db.incrementalUpdateSql", "select id, name, dateField,"
        + " readers_users from " + tableName + " where dateField > ?");
    String row = "row" + randomId;
    String createSQL = String.format(
        "create table %s (id varchar(32) unique not null, "
            + "name varchar(128),  dateField timestamp, readers_users varchar(128))",
        tableName);
    String insertSQL = String.format(
        "insert into %s (id, name,  dateField, readers_users) "
            + "values ('%s', '%s', '%s', 'google:%s')",
        tableName, row, "Jones May", "1907-10-10T14:21:23.400Z", testUser1);

    try {
      DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.H2);
      List<String> query = ImmutableList.of(createSQL, insertSQL);
      String[] args = setupDataAndConfiguration(dbConnection, config, query);
      MockItem item = new MockItem.Builder(getItemId(row))
          .setTitle(row)
          .setSourceRepositoryUrl(row)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .build();
      IndexingApplication dbConnector = runConnector(args);
      Awaitility.await()
          .atMost(CONNECTOR_RUN_TIME)
          .pollInterval(CONNECTOR_RUN_POLL_INTERVAL)
          .until(() -> ConnectorStats.getSuccessfulFullTraversalsCount() > 0);
      testUtils.waitUntilEqual(getItemId(row), item.getItem());
      ItemAcl expectedAcl =
          new ItemAcl()
              .setReaders(
                  Arrays.asList(
                      Acl.getGoogleUserPrincipal(testUser1)));
      assertEquals(expectedAcl, v1Client.getItem(getItemId(row)).getAcl());

      String updateSQL =
          String.format(
              "update %s set dateField = CURRENT_TIMESTAMP(), readers_users = "
                  + "'google:%s' where id = '%s'",
              tableName, testUser2, row);
      executeDatabaseStatement(dbConnection,
          Collections.singletonList(updateSQL));
      int traversalCount = ConnectorStats.getSuccessfulIncrementalTraversalsCount();
      Awaitility.await()
          .atMost(CONNECTOR_RUN_TIME)
          .pollInterval(CONNECTOR_RUN_POLL_INTERVAL)
          .until(() -> ConnectorStats.
              getSuccessfulIncrementalTraversalsCount() > traversalCount + 1);
      dbConnector.shutdown("Shutdown Initiated");
      MockItem updatedItem = new MockItem.Builder(getItemId(row))
          .setTitle(row)
          .setSourceRepositoryUrl(row)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .build();
      testUtils.waitUntilEqual(getItemId(row), updatedItem.getItem());
      expectedAcl =
          new ItemAcl()
              .setReaders(
                  Arrays.asList(
                      Acl.getGoogleUserPrincipal(testUser2)));
      assertEquals(expectedAcl, v1Client.getItem(getItemId(row)).getAcl());
    } finally {
      v1Client.deleteItemsIfExist(ImmutableList.of(getItemId(row)));
    }
  }

  @Test
  public void incrementalTraversal_deleteAcl_succeeds()
      throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "Select id, name, dateField,"
        + " readers_users from " + tableName);
    config.setProperty("db.allColumns", "id, name, dateField, readers_users");
    config.setProperty("connector.runOnce", "false");
    config.setProperty("schedule.incrementalTraversalIntervalSecs", "10");
    config.setProperty("db.incrementalUpdateSql", "select id, name, dateField,"
        + " readers_users from " + tableName + " where dateField > ?");
    String row = "row" + randomId;
    String createSQL = String.format(
        "create table %s (id varchar(32) unique not null, "
            + "name varchar(128),  dateField timestamp, readers_users varchar(128))",
        tableName);
    String insertSQL = String.format(
        "insert into %s (id, name,  dateField, readers_users) "
            + "values ('%s', '%s', '%s', 'google:%s, google:%s')",
        tableName, row, "Jones May", "1907-10-10T14:21:23.400Z", testUser1, testUser2);

    try {
      DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.H2);
      List<String> query = ImmutableList.of(createSQL, insertSQL);
      String[] args = setupDataAndConfiguration(dbConnection, config, query);
      MockItem item = new MockItem.Builder(getItemId(row))
          .setTitle(row)
          .setSourceRepositoryUrl(row)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .build();
      IndexingApplication dbConnector = runConnector(args);
      Awaitility.await()
          .atMost(CONNECTOR_RUN_TIME)
          .pollInterval(CONNECTOR_RUN_POLL_INTERVAL)
          .until(() -> ConnectorStats.getSuccessfulFullTraversalsCount() > 0);
      testUtils.waitUntilEqual(getItemId(row), item.getItem());
      ItemAcl expectedAcl =
          new ItemAcl()
              .setReaders(
                  Arrays.asList(
                      Acl.getGoogleUserPrincipal(testUser1),
                      Acl.getGoogleUserPrincipal(testUser2)));
      assertEquals(expectedAcl, v1Client.getItem(getItemId(row)).getAcl());

      String updateSQL =
          String.format(
              "update %s set dateField = CURRENT_TIMESTAMP(), readers_users = "
                  + "'google:%s' where id = '%s'",
              tableName, testUser2, row);
      executeDatabaseStatement(dbConnection,
          Collections.singletonList(updateSQL));
      int traversalCount = ConnectorStats.getSuccessfulIncrementalTraversalsCount();
      Awaitility.await()
          .atMost(CONNECTOR_RUN_TIME)
          .pollInterval(CONNECTOR_RUN_POLL_INTERVAL)
          .until(() -> ConnectorStats.
              getSuccessfulIncrementalTraversalsCount() > traversalCount + 1);
      dbConnector.shutdown("Shutdown Initiated");
      MockItem updatedItem = new MockItem.Builder(getItemId(row))
          .setTitle(row)
          .setSourceRepositoryUrl(row)
          .setContentLanguage("en-US")
          .setItemType(ItemType.CONTENT_ITEM.toString())
          .build();
      testUtils.waitUntilEqual(getItemId(row), updatedItem.getItem());
      expectedAcl =
          new ItemAcl()
              .setReaders(
                  Arrays.asList(
                      Acl.getGoogleUserPrincipal(testUser2)));
      assertEquals(expectedAcl, v1Client.getItem(getItemId(row)).getAcl());
    } finally {
      v1Client.deleteItemsIfExist(ImmutableList.of(getItemId(row)));
    }
  }

  @Test
  public void queryPagination_offset() throws IOException, SQLException, InterruptedException {
    String randomId = Util.getRandomId();
    String tableName = name.getMethodName() + randomId;
    Properties config = new Properties();
    config.setProperty(
        "db.allRecordsSql", "Select id, name, phone from " + tableName + " OFFSET ? LIMIT 2");
    config.setProperty("db.allColumns", "id, name, phone");
    config.setProperty("db.allRecordsSql.pagination", "offset");
    String row1 = "x1" + randomId;
    String row2 = "x2" + randomId;
    String row3 = "x3" + randomId;
    List<String> query = new ArrayList<>();
    List<String> mockItems = new ArrayList<>();
    query.add(
        "create table "
            + tableName
            + "(id varchar(32) unique not null, name varchar(128), phone varchar(16))");
    query.add(
        "insert into "
            + tableName
            + " (id, name, phone)"
            + " values ('"
            + row1
            + "', 'Jones May', '2134')");
    query.add(
        "insert into "
            + tableName
            + " (id, name, phone)"
            + " values ('"
            + row2
            + "', 'Joe Smith', '9848')");
    query.add(
        "insert into "
            + tableName
            + " (id, name, phone)"
            + " values ('"
            + row3
            + "', 'Mike Brown', '3476')");
    try {
      DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.H2);
      String[] args = setupDataAndConfiguration(dbConnection, config, query);
      MockItem itemId1 =
          new MockItem.Builder(getItemId(row1))
              .setTitle(row1)
              .setSourceRepositoryUrl(row1)
              .setContentLanguage("en-US")
              .setItemType(ItemType.CONTENT_ITEM.toString())
              .build();
      MockItem itemId2 =
          new MockItem.Builder(getItemId(row2))
              .setTitle(row2)
              .setSourceRepositoryUrl(row2)
              .setContentLanguage("en-US")
              .setItemType(ItemType.CONTENT_ITEM.toString())
              .build();
      MockItem itemId3 =
          new MockItem.Builder(getItemId(row3))
              .setTitle(row3)
              .setSourceRepositoryUrl(row3)
              .setContentLanguage("en-US")
              .setItemType(ItemType.CONTENT_ITEM.toString())
              .build();
      mockItems.addAll(asList(getItemId(row1), getItemId(row2), getItemId(row3)));
      runAwaitConnector(args);
      testUtils.waitUntilEqual(getItemId(row1), itemId1.getItem());
      testUtils.waitUntilEqual(getItemId(row2), itemId2.getItem());
      testUtils.waitUntilEqual(getItemId(row3), itemId3.getItem());
    } finally {
      v1Client.deleteItemsIfExist(mockItems);
    }
  }

  @Test
  @Parameters({
    "tinyint, 111",
    "smallint, 32555",
    "integer, 53219",
    "char(9), test data",
    "varchar(32), testing data",
    "text, test data"
  })
  public void basicDataTypes_inMYSQL_succeeds(String type, String data)
      throws IOException, InterruptedException, SQLException {
    String randomId = Util.getRandomId();
    String tableName = "basicDataTypes_inMYSQL_succeeds" + randomId;
    Properties config = new Properties();
    config.setProperty("db.allRecordsSql", "Select id, text from " + tableName);
    config.setProperty("db.allColumns", "id, text");
    config.setProperty("url.columns", "id");
    config.setProperty("url.format", "http://example.com/employee/{0}");
    config.setProperty("itemMetadata.objectType.defaultValue", "myMockDataObject");

    String row = "row" + randomId;
    List<String> query = new ArrayList<>();
    List<String> mockItems = new ArrayList<>();

    String createSQL = String.format(
        "create table %s (id varchar(32) unique not null, text %s)",
        tableName, type);
    String insertSQL = String.format(
        "insert into %s (id, text) values ('%s', '%s')",
        tableName, row, data);
    query.add(createSQL);
    query.add(insertSQL);

    DatabaseConnectionParams dbConnection = getDatabaseConnectionParams(Database.MYSQL);
    String[] args = setupDataAndConfiguration(dbConnection, config, query);
    MockItem itemId1 = new MockItem.Builder(getItemId(row))
        .setTitle(row)
        .setSourceRepositoryUrl("http://example.com/employee/" + row)
        .setContentLanguage("en-US")
        .setItemType(ItemType.CONTENT_ITEM.toString())
        .addValue("text", data)
        .setObjectType("myMockDataObject")
        .build();
    mockItems.addAll(asList(getItemId(row)));

    try {
      runAwaitConnector(args);
      testUtils.waitUntilEqual(getItemId(row), itemId1.getItem());
      verifyStructuredData(getItemId(row), "myMockDataObject", itemId1.getItem());
    } finally {
      executeDatabaseStatement(dbConnection, ImmutableList.of("drop table " + tableName));
      v1Client.deleteItemsIfExist(mockItems);
    }
  }

  private void verifyStructuredData(String itemId, String schemaObjectType, Item expectedItem)
      throws IOException {
    testUtils.waitUntilEqual(itemId, expectedItem);
    StructuredDataHelper.assertStructuredData(v1Client.getItem(itemId),
        expectedItem, schemaObjectType);
  }

  private void runAwaitConnector(String[] args) throws InterruptedException {
    IndexingApplication dbConnector = runConnector(args);
    dbConnector.awaitTerminated();
  }

  private static IndexingApplication runConnector(String[] args) throws InterruptedException {
    IndexingApplication dbConnector = new IndexingApplication.Builder(
        new FullTraversalConnector(new DatabaseRepository()), args)
        .build();
    dbConnector.start();
    return dbConnector;
  }

  private String getItemId(String name) {
    return Util.getItemId(indexingSourceId, name);
  }
}

/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.snowflake;

import static io.airbyte.integrations.destination.snowflake.SnowflakeDestination.isInternalStaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Preconditions;
import io.airbyte.commons.io.IOs;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.resources.MoreResources;
import io.airbyte.commons.string.Strings;
import io.airbyte.db.jdbc.JdbcUtils;
import io.airbyte.integrations.base.JavaBaseConstants;
import io.airbyte.integrations.destination.ExtendedNameTransformer;
import io.airbyte.integrations.standardtest.destination.DataArgumentsProvider;
import io.airbyte.integrations.standardtest.destination.DestinationAcceptanceTest;
import io.airbyte.protocol.models.AirbyteCatalog;
import io.airbyte.protocol.models.AirbyteMessage;
import io.airbyte.protocol.models.CatalogHelpers;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

public class SnowflakeInternalStagingDestinatiomAcceptanceTest extends DestinationAcceptanceTest {

  private JsonNode baseConfig;
  private JsonNode config;
  private final ExtendedNameTransformer namingResolver = new SnowflakeSQLNameTransformer();

  @Override
  protected String getImageName() {
    return "airbyte/destination-snowflake:dev";
  }

  @Override
  protected JsonNode getConfig() {
    return config;
  }

  public JsonNode getStaticConfig() {
    final JsonNode internalStagingConfig = Jsons.deserialize(IOs.readFile(Path.of("secrets/internal_staging_config.json")));
    Preconditions.checkArgument(!SnowflakeDestination.isS3Copy(internalStagingConfig));
    Preconditions.checkArgument(!SnowflakeDestination.isGcsCopy(internalStagingConfig));
    Preconditions.checkArgument(isInternalStaging(internalStagingConfig));
    return internalStagingConfig;
  }

  @Override
  protected JsonNode getFailCheckConfig() {
    final JsonNode invalidConfig = Jsons.clone(config);
    ((ObjectNode) invalidConfig).put("password", "wrong password");
    return invalidConfig;
  }

  @Override
  protected List<JsonNode> retrieveRecords(final TestDestinationEnv env,
                                           final String streamName,
                                           final String namespace,
                                           final JsonNode streamSchema)
      throws Exception {
    return retrieveRecordsFromTable(namingResolver.getRawTableName(streamName), namingResolver.getIdentifier(namespace))
        .stream()
        .map(j -> Jsons.deserialize(j.get(JavaBaseConstants.COLUMN_NAME_DATA.toUpperCase()).asText()))
        .collect(Collectors.toList());
  }

  @Override
  protected boolean supportsNormalization() {
    return true;
  }

  @Override
  protected boolean supportsDBT() {
    return true;
  }

  @Override
  protected boolean implementsNamespaces() {
    return true;
  }

  @Override
  protected List<JsonNode> retrieveNormalizedRecords(final TestDestinationEnv testEnv, final String streamName, final String namespace)
      throws Exception {
    final String tableName = namingResolver.getIdentifier(streamName);
    final String schema = namingResolver.getIdentifier(namespace);
    return retrieveRecordsFromTable(tableName, schema);
  }

  @Override
  protected List<String> resolveIdentifier(final String identifier) {
    final List<String> result = new ArrayList<>();
    final String resolved = namingResolver.getIdentifier(identifier);
    result.add(identifier);
    result.add(resolved);
    if (!resolved.startsWith("\"")) {
      result.add(resolved.toLowerCase());
      result.add(resolved.toUpperCase());
    }
    return result;
  }

  private List<JsonNode> retrieveRecordsFromTable(final String tableName, final String schema) throws SQLException, InterruptedException {
    return SnowflakeDatabase.getDatabase(getConfig()).bufferedResultSetQuery(
        connection -> connection.createStatement()
            .executeQuery(String.format("SELECT * FROM %s.%s ORDER BY %s ASC;", schema, tableName, JavaBaseConstants.COLUMN_NAME_EMITTED_AT)),
        JdbcUtils.getDefaultSourceOperations()::rowToJson);
  }

  // for each test we create a new schema in the database. run the test in there and then remove it.
  @Override
  protected void setup(final TestDestinationEnv testEnv) throws Exception {
    final String schemaName = Strings.addRandomSuffix("integration_test", "_", 5);
    final String createSchemaQuery = String.format("CREATE SCHEMA %s", schemaName);

    baseConfig = getStaticConfig();
    SnowflakeDatabase.getDatabase(baseConfig).execute(createSchemaQuery);

    final JsonNode configForSchema = Jsons.clone(baseConfig);
    ((ObjectNode) configForSchema).put("schema", schemaName);
    config = configForSchema;
  }

  @Override
  protected void tearDown(final TestDestinationEnv testEnv) throws Exception {
    final String dropSchemaQuery = String.format("DROP SCHEMA IF EXISTS %s", config.get("schema").asText());
    SnowflakeDatabase.getDatabase(baseConfig).execute(dropSchemaQuery);
  }

}
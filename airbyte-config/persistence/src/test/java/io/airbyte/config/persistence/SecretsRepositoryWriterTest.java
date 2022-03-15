/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.config.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.airbyte.commons.json.Jsons;
import io.airbyte.config.AirbyteConfig;
import io.airbyte.config.ConfigSchema;
import io.airbyte.config.DestinationConnection;
import io.airbyte.config.SourceConnection;
import io.airbyte.config.StagingConfiguration;
import io.airbyte.config.StandardDestinationDefinition;
import io.airbyte.config.StandardSourceDefinition;
import io.airbyte.config.persistence.split_secrets.MemorySecretPersistence;
import io.airbyte.config.persistence.split_secrets.RealSecretsHydrator;
import io.airbyte.config.persistence.split_secrets.SecretCoordinate;
import io.airbyte.config.persistence.split_secrets.SecretPersistence;
import io.airbyte.protocol.models.ConnectorSpecification;
import io.airbyte.validation.json.JsonValidationException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SecretsRepositoryWriterTest {

  private static final UUID UUID1 = UUID.randomUUID();

  private static final ConnectorSpecification SPEC = new ConnectorSpecification()
      .withConnectionSpecification(Jsons.deserialize(
          "{ \"properties\": { \"username\": { \"type\": \"string\" }, \"password\": { \"type\": \"string\", \"airbyte_secret\": true } } }"));

  private static final String SECRET = "abc";
  private static final JsonNode FULL_CONFIG = Jsons.deserialize(String.format("{ \"username\": \"airbyte\", \"password\": \"%s\"}", SECRET));

  private static final SourceConnection SOURCE_WITH_FULL_CONFIG = new SourceConnection()
      .withSourceId(UUID1)
      .withSourceDefinitionId(UUID.randomUUID())
      .withConfiguration(FULL_CONFIG);

  private static final DestinationConnection DESTINATION_WITH_FULL_CONFIG = new DestinationConnection()
      .withDestinationId(UUID1)
      .withConfiguration(FULL_CONFIG);

  private static final StandardSourceDefinition SOURCE_DEF = new StandardSourceDefinition()
      .withSourceDefinitionId(SOURCE_WITH_FULL_CONFIG.getSourceDefinitionId())
      .withSpec(SPEC);

  private static final StandardDestinationDefinition DEST_DEF = new StandardDestinationDefinition()
      .withDestinationDefinitionId(DESTINATION_WITH_FULL_CONFIG.getDestinationDefinitionId())
      .withSpec(SPEC);

  private ConfigRepository configRepository;
  private MemorySecretPersistence longLivedSecretPersistence;
  private MemorySecretPersistence ephemeralSecretPersistence;
  private SecretsRepositoryWriter secretsRepositoryWriter;

  private RealSecretsHydrator longLivedSecretsHydrator;
  private SecretsRepositoryReader longLivedSecretsRepositoryReader;
  private RealSecretsHydrator ephemeralSecretsHydrator;
  private SecretsRepositoryReader ephemeralSecretsRepositoryReader;

  @BeforeEach
  void setup() {
    configRepository = spy(mock(ConfigRepository.class));
    longLivedSecretPersistence = new MemorySecretPersistence();
    ephemeralSecretPersistence = new MemorySecretPersistence();

    secretsRepositoryWriter = new SecretsRepositoryWriter(
        configRepository,
        Optional.of(longLivedSecretPersistence),
        Optional.of(ephemeralSecretPersistence));

    longLivedSecretsHydrator = new RealSecretsHydrator(longLivedSecretPersistence);
    longLivedSecretsRepositoryReader = new SecretsRepositoryReader(configRepository, longLivedSecretsHydrator);

    ephemeralSecretsHydrator = new RealSecretsHydrator(ephemeralSecretPersistence);
    ephemeralSecretsRepositoryReader = new SecretsRepositoryReader(configRepository, ephemeralSecretsHydrator);
  }

  @Test
  void testWriteSourceConnection() throws JsonValidationException, IOException, ConfigNotFoundException {
    doThrow(ConfigNotFoundException.class).when(configRepository).getSourceConnection(UUID1);

    secretsRepositoryWriter.writeSourceConnection(SOURCE_WITH_FULL_CONFIG, SPEC);
    final SecretCoordinate coordinate = getCoordinateFromSecretsStore(longLivedSecretPersistence);

    assertNotNull(coordinate);
    final SourceConnection partialSource = injectCoordinateIntoSource(coordinate.getFullCoordinate());
    verify(configRepository).writeSourceConnectionNoSecrets(partialSource);
    final Optional<String> persistedSecret = longLivedSecretPersistence.read(coordinate);
    assertTrue(persistedSecret.isPresent());
    assertEquals(SECRET, persistedSecret.get());

    // verify that the round trip works.
    reset(configRepository);
    when(configRepository.getSourceConnection(UUID1)).thenReturn(partialSource);
    assertEquals(SOURCE_WITH_FULL_CONFIG, longLivedSecretsRepositoryReader.getSourceConnectionWithSecrets(UUID1));
  }

  @Test
  void testWriteDestinationConnection() throws JsonValidationException, IOException, ConfigNotFoundException {
    doThrow(ConfigNotFoundException.class).when(configRepository).getDestinationConnection(UUID1);

    secretsRepositoryWriter.writeDestinationConnection(DESTINATION_WITH_FULL_CONFIG, SPEC);
    final SecretCoordinate coordinate = getCoordinateFromSecretsStore(longLivedSecretPersistence);

    assertNotNull(coordinate);
    final DestinationConnection partialDestination = injectCoordinateIntoDestination(coordinate.getFullCoordinate());
    verify(configRepository).writeDestinationConnectionNoSecrets(partialDestination);
    final Optional<String> persistedSecret = longLivedSecretPersistence.read(coordinate);
    assertTrue(persistedSecret.isPresent());
    assertEquals(SECRET, persistedSecret.get());

    // verify that the round trip works.
    reset(configRepository);
    when(configRepository.getDestinationConnection(UUID1)).thenReturn(partialDestination);
    assertEquals(DESTINATION_WITH_FULL_CONFIG, longLivedSecretsRepositoryReader.getDestinationConnectionWithSecrets(UUID1));
  }

  @Test
  void testStatefulSplitEphemeralSecrets() throws JsonValidationException, IOException, ConfigNotFoundException {
    final JsonNode split = secretsRepositoryWriter.statefulSplitEphemeralSecrets(
        SOURCE_WITH_FULL_CONFIG.getConfiguration(),
        SPEC);
    final SecretCoordinate coordinate = getCoordinateFromSecretsStore(ephemeralSecretPersistence);

    assertNotNull(coordinate);
    final Optional<String> persistedSecret = ephemeralSecretPersistence.read(coordinate);
    assertTrue(persistedSecret.isPresent());
    assertEquals(SECRET, persistedSecret.get());

    // verify that the round trip works.
    assertEquals(SOURCE_WITH_FULL_CONFIG.getConfiguration(), ephemeralSecretsHydrator.hydrate(split));
  }

  @SuppressWarnings("unchecked")
  @Test
  void testReplaceAllConfigs() throws IOException {
    final Map<AirbyteConfig, Stream<?>> configs = new HashMap<>();
    configs.put(ConfigSchema.STANDARD_SOURCE_DEFINITION, Stream.of(Jsons.clone(SOURCE_DEF)));
    configs.put(ConfigSchema.STANDARD_DESTINATION_DEFINITION, Stream.of(Jsons.clone(DEST_DEF)));
    configs.put(ConfigSchema.SOURCE_CONNECTION, Stream.of(Jsons.clone(SOURCE_WITH_FULL_CONFIG)));
    configs.put(ConfigSchema.DESTINATION_CONNECTION, Stream.of(Jsons.clone(DESTINATION_WITH_FULL_CONFIG)));

    secretsRepositoryWriter.replaceAllConfigs(configs, false);

    final ArgumentCaptor<Map<AirbyteConfig, Stream<?>>> argument = ArgumentCaptor.forClass(Map.class);
    verify(configRepository).replaceAllConfigsNoSecrets(argument.capture(), eq(false));
    final Map<AirbyteConfig, ? extends List<?>> actual = argument.getValue().entrySet()
        .stream()
        .collect(Collectors.toMap(Entry::getKey, e -> e.getValue().collect(Collectors.toList())));

    assertEquals(SOURCE_DEF, actual.get(ConfigSchema.STANDARD_SOURCE_DEFINITION).get(0));
    assertEquals(DEST_DEF, actual.get(ConfigSchema.STANDARD_DESTINATION_DEFINITION).get(0));

    // we can't easily get the pointer, so verify the secret has been stripped out and then make sure
    // the rest of the object meets expectations.
    final SourceConnection actualSource = (SourceConnection) actual.get(ConfigSchema.SOURCE_CONNECTION).get(0);
    assertTrue(actualSource.getConfiguration().get("password").has("_secret"));
    ((ObjectNode) actualSource.getConfiguration()).remove("password");
    final SourceConnection expectedSource = Jsons.clone(SOURCE_WITH_FULL_CONFIG);
    ((ObjectNode) expectedSource.getConfiguration()).remove("password");
    assertEquals(expectedSource, actualSource);

    final DestinationConnection actualDest = (DestinationConnection) actual.get(ConfigSchema.DESTINATION_CONNECTION).get(0);
    assertTrue(actualDest.getConfiguration().get("password").has("_secret"));
    ((ObjectNode) actualDest.getConfiguration()).remove("password");
    final DestinationConnection expectedDest = Jsons.clone(DESTINATION_WITH_FULL_CONFIG);
    ((ObjectNode) expectedDest.getConfiguration()).remove("password");
    assertEquals(expectedDest, actualDest);
  }

  // this only works if the secrets store has one secret.
  private SecretCoordinate getCoordinateFromSecretsStore(final MemorySecretPersistence secretPersistence) {
    return secretPersistence.getMap()
        .keySet()
        .stream()
        .findFirst()
        .orElse(null);
  }

  private static JsonNode injectCoordinate(final String coordinate) {
    return Jsons.deserialize(String.format("{ \"username\": \"airbyte\", \"password\": { \"_secret\": \"%s\" } }", coordinate));
  }

  private static SourceConnection injectCoordinateIntoSource(final String coordinate) {
    return Jsons.clone(SOURCE_WITH_FULL_CONFIG).withConfiguration(injectCoordinate(coordinate));
  }

  private static DestinationConnection injectCoordinateIntoDestination(final String coordinate) {
    return Jsons.clone(DESTINATION_WITH_FULL_CONFIG).withConfiguration(injectCoordinate(coordinate));
  }

  @Test
  public void testWriteStagingConfiguration() throws JsonValidationException, ConfigNotFoundException, IOException {
    final UUID destinationDefinitionId = UUID.randomUUID();
    final JsonNode secretPayload = Jsons.jsonNode(sortMap(Map.of("name", "John", "age", "30", "car", "tesla")));
    assertEquals("{\"age\":\"30\",\"car\":\"tesla\",\"name\":\"John\"}", secretPayload.toString());
    final StagingConfiguration stagingConfiguration = new StagingConfiguration()
        .withDestinationDefinitionId(destinationDefinitionId)
        .withConfiguration(secretPayload);

    doThrow(new ConfigNotFoundException(ConfigSchema.STAGING_CONFIGURATION, destinationDefinitionId.toString()))
        .when(configRepository).getStagingConfigurationNoSecrets(destinationDefinitionId);
    secretsRepositoryWriter.writeStagingConfiguration(stagingConfiguration);

    assertEquals(1, longLivedSecretPersistence.getMap().size());
    final String payloadSavedInPersistence = longLivedSecretPersistence.getMap().values().stream().toList().get(0);
    assertEquals(secretPayload.toString(), payloadSavedInPersistence);
    final SecretCoordinate secretCoordinate = longLivedSecretPersistence.getMap().keySet().stream().toList().get(0);

    verify(configRepository).writeStagingConfigurationNoSecrets(
        Jsons.clone(stagingConfiguration.withConfiguration(Jsons.jsonNode(Map.of("_secret", secretCoordinate.getFullCoordinate())))));
  }

  @Test
  public void testWriteSameStagingConfiguration() throws JsonValidationException, ConfigNotFoundException, IOException {
    final ConfigRepository configRepository = mock(ConfigRepository.class);
    final SecretPersistence secretPersistence = mock(SecretPersistence.class);
    final SecretsRepositoryWriter secretsRepositoryWriter =
        spy(new SecretsRepositoryWriter(configRepository, Optional.of(secretPersistence), Optional.of(secretPersistence)));

    final UUID destinationDefinitionId = UUID.fromString("13fb9a84-6bfa-4801-8f5e-ce717677babf");
    final JsonNode secretPayload = Jsons.jsonNode(sortMap(Map.of("name", "John", "age", "30", "car", "null")));
    assertEquals("{\"age\":\"30\",\"car\":\"null\",\"name\":\"John\"}", secretPayload.toString());
    final StagingConfiguration stagingConfiguration = new StagingConfiguration()
        .withDestinationDefinitionId(destinationDefinitionId)
        .withConfiguration(secretPayload);
    final SecretCoordinate secretCoordinate = new SecretCoordinate(
        "destination_definition_13fb9a84-6bfa-4801-8f5e-ce717677babf_secret_e86e2eab-af9b-42a3-b074-b923b4fa617e", 1);

    doReturn(Jsons.clone(stagingConfiguration).withConfiguration(Jsons.jsonNode(
        Map.of("_secret", secretCoordinate.getFullCoordinate()))))
            .when(configRepository).getStagingConfigurationNoSecrets(destinationDefinitionId);

    doReturn(Optional.of(secretPayload.toString())).when(secretPersistence).read(secretCoordinate);
    secretsRepositoryWriter.writeStagingConfiguration(stagingConfiguration);

    verify(secretPersistence).write(secretCoordinate, secretPayload.toString());
    verify(configRepository).writeStagingConfigurationNoSecrets(Jsons.clone(stagingConfiguration.withConfiguration(Jsons.jsonNode(
        Map.of("_secret", secretCoordinate.getFullCoordinate())))));
  }

  @Test
  public void testWriteDifferentStagingConfiguration() throws JsonValidationException, ConfigNotFoundException, IOException {
    final ConfigRepository configRepository = mock(ConfigRepository.class);
    final SecretPersistence secretPersistence = mock(SecretPersistence.class);
    final SecretsRepositoryWriter secretsRepositoryWriter =
        spy(new SecretsRepositoryWriter(configRepository, Optional.of(secretPersistence), Optional.of(secretPersistence)));

    final UUID destinationDefinitionId = UUID.fromString("13fb9a84-6bfa-4801-8f5e-ce717677babf");
    final JsonNode oldSecretPayload = Jsons.jsonNode(sortMap(Map.of("name", "John", "age", "30", "car", "null")));
    assertEquals("{\"age\":\"30\",\"car\":\"null\",\"name\":\"John\"}", oldSecretPayload.toString());
    final JsonNode newSecretPayload = Jsons.jsonNode(sortMap(Map.of("name", "John", "age", "30", "car", "abcd")));
    assertEquals("{\"age\":\"30\",\"car\":\"abcd\",\"name\":\"John\"}", newSecretPayload.toString());
    final StagingConfiguration stagingConfiguration = new StagingConfiguration()
        .withDestinationDefinitionId(destinationDefinitionId)
        .withConfiguration(newSecretPayload);

    final SecretCoordinate oldSecretCoordinate = new SecretCoordinate(
        "destination_definition_13fb9a84-6bfa-4801-8f5e-ce717677babf_secret_e86e2eab-af9b-42a3-b074-b923b4fa617e", 1);

    doReturn(Jsons.clone(stagingConfiguration).withConfiguration(Jsons.jsonNode(
        Map.of("_secret", oldSecretCoordinate.getFullCoordinate()))))
            .when(configRepository).getStagingConfigurationNoSecrets(destinationDefinitionId);

    final SecretCoordinate newSecretCoordinate = new SecretCoordinate(
        "destination_definition_13fb9a84-6bfa-4801-8f5e-ce717677babf_secret_e86e2eab-af9b-42a3-b074-b923b4fa617e", 2);
    doReturn(Optional.of(oldSecretPayload.toString())).when(secretPersistence).read(oldSecretCoordinate);
    secretsRepositoryWriter.writeStagingConfiguration(stagingConfiguration);

    verify(secretPersistence).write(newSecretCoordinate, newSecretPayload.toString());
    verify(configRepository).writeStagingConfigurationNoSecrets(Jsons.clone(stagingConfiguration.withConfiguration(Jsons.jsonNode(
        Map.of("_secret", newSecretCoordinate.getFullCoordinate())))));
  }

  private Map<String, String> sortMap(Map<String, String> originalMap) {
    return originalMap.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (oldValue, newValue) -> newValue, TreeMap::new));
  }

}

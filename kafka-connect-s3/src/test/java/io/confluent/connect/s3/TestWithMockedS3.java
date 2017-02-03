/*
 * Copyright 2017 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.confluent.connect.s3;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AnonymousAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import io.findify.s3mock.S3Mock;
import org.apache.kafka.common.TopicPartition;
import org.junit.After;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.confluent.connect.s3.format.avro.AvroUtils;
import io.confluent.connect.s3.util.FileUtils;
import io.confluent.connect.storage.common.StorageCommonConfig;

public class TestWithMockedS3 extends S3SinkConnectorTestBase {

  private static final Logger log = LoggerFactory.getLogger(TestWithMockedS3.class);

  protected S3Mock s3mock;
  protected String port;
  @Rule
  public TemporaryFolder s3mockRoot = new TemporaryFolder();

  @Override
  protected Map<String, String> createProps() {
    Map<String, String> props = super.createProps();
    props.put(StorageCommonConfig.DIRECTORY_DELIM_CONFIG, "_");
    props.put(StorageCommonConfig.FILE_DELIM_CONFIG, "#");
    return props;
  }

  //@Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    port = url.substring(url.lastIndexOf(":") + 1);
    File s3mockDir = s3mockRoot.newFolder("s3-tests-" + UUID.randomUUID().toString());
    System.out.println("Create folder: " + s3mockDir.getCanonicalPath());
    s3mock = S3Mock.create(Integer.parseInt(port), s3mockDir.getCanonicalPath());
    s3mock.start();
  }

  @After
  @Override
  public void tearDown() throws Exception {
    super.tearDown();
    s3mock.stop();
  }

  public static List<S3ObjectSummary> listObjects(String bucket, String prefix, AmazonS3 s3) {
    List<S3ObjectSummary> objects = new ArrayList<>();
    ObjectListing listing;

    if (prefix == null) {
      listing = s3.listObjects(bucket);
    } else {
      listing = s3.listObjects(bucket, prefix);
    }

    objects.addAll(listing.getObjectSummaries());
    while (listing.isTruncated()) {
      listing = s3.listNextBatchOfObjects(listing);
      objects.addAll(listing.getObjectSummaries());
    }

    return objects;
  }

  public static Collection<Object> readRecords(String topicsDir, String directory, TopicPartition tp, long startOffset,
                                               String extension, String zeroPadFormat, String bucketName,
                                               AmazonS3 s3) throws IOException {
      String fileKey = FileUtils.fileKeyToCommit(topicsDir, directory, tp, startOffset, extension, zeroPadFormat);
      return readRecords(bucketName, fileKey, s3);
  }

  public static Collection<Object> readRecords(String bucketName, String fileKey, AmazonS3 s3) throws IOException {
      log.debug("Reading records from bucket '{}' key '{}': ", bucketName, fileKey);
      InputStream in = s3.getObject(bucketName, fileKey).getObjectContent();

      return AvroUtils.getRecords(in);
  }

  @Override
  public AmazonS3 newS3Client(S3SinkConnectorConfig config) {
    final AWSCredentialsProvider provider = new AWSCredentialsProvider() {
      private final AnonymousAWSCredentials credentials = new AnonymousAWSCredentials();
      @Override
      public AWSCredentials getCredentials() {
        return credentials;
      }

      @Override
      public void refresh() {
      }
    };

    AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard()
               .withAccelerateModeEnabled(config.getBoolean(S3SinkConnectorConfig.WAN_MODE_CONFIG))
               .withPathStyleAccessEnabled(true)
               .withCredentials(provider);

    builder = url == null ?
                  builder.withRegion(config.getString(S3SinkConnectorConfig.REGION_CONFIG)) :
                  builder.withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(url, ""));

    return builder.build();
  }

}
/**
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.gcsio;

import com.google.cloud.hadoop.util.LogUtil;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MetadataReadOnlyGoogleCloudStorage holds a collection of Storage object/bucket metadata entries
 * and serves listObjectNames, listObjectInfo, getItemInfos, and getItemInfo exclusively from the
 * in-memory cache. All other operations will throw an UnsupportedOperationException. For the time
 * being, such unsupported operations include bucket info list operations like listBucketInfo and
 * listBucketNames.
 *
 * This instance will supplement fake GoogleCloudStorageItemInfos for pure implicit directories
 * if there is no metadata entry for the directory object itself. This means that some directory
 * objects will list info that is inconsistent with an actual listing.
 */
public class MetadataReadOnlyGoogleCloudStorage
    implements GoogleCloudStorage {
  // Logger.
  public static final LogUtil log = new LogUtil(MetadataReadOnlyGoogleCloudStorage.class);

  // Immutable cache of all metadata held by this instance, populated at construction time.
  private final DirectoryListCache resourceCache = new InMemoryDirectoryListCache();

  // TODO(user): Consolidate this with the equivalent object in LaggedGoogleCloudStorage.java.
  private static final Function<GoogleCloudStorageItemInfo, String> ITEM_INFO_TO_NAME =
      new Function<GoogleCloudStorageItemInfo, String>() {
        @Override
        public String apply(GoogleCloudStorageItemInfo itemInfo) {
          return itemInfo.getObjectName();
        }
      };

  /**
   * Constructs a MetadataReadOnlyGoogleCloudStorage which can be used for temporary contexts
   * where only object metadata read operations will be used through the GoogleCloudStorage
   * interface.
   *
   * @param itemInfos The collection of item infos with which to serve all list/get object requests.
   */
  public MetadataReadOnlyGoogleCloudStorage(Collection<GoogleCloudStorageItemInfo> itemInfos)
      throws IOException {
    // Entries never expire for this use case.
    resourceCache.getMutableConfig().setMaxEntryAgeMillis(Long.MAX_VALUE);
    resourceCache.getMutableConfig().setMaxInfoAgeMillis(Long.MAX_VALUE);

    log.debug("Populating cache with %d entries.", itemInfos.size());
    for (GoogleCloudStorageItemInfo itemInfo : itemInfos) {
      resourceCache.putResourceId(itemInfo.getResourceId()).setItemInfo(itemInfo);
    }
  }

  @Override
  public WritableByteChannel create(final StorageResourceId resourceId)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public WritableByteChannel create(StorageResourceId resourceId, CreateObjectOptions options)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void createEmptyObject(StorageResourceId resourceId)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void createEmptyObject(StorageResourceId resourceId, CreateObjectOptions options)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void createEmptyObjects(List<StorageResourceId> resourceIds)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void createEmptyObjects(List<StorageResourceId> resourceIds, CreateObjectOptions options)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public SeekableReadableByteChannel open(StorageResourceId resourceId)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void create(String bucketName)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void deleteBuckets(List<String> bucketNames)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void deleteObjects(List<StorageResourceId> fullObjectNames)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void copy(String srcBucketName, List<String> srcObjectNames,
      String dstBucketName, List<String> dstObjectNames)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> listBucketNames()
      throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<GoogleCloudStorageItemInfo> listBucketInfo()
      throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> listObjectNames(
      String bucketName, String objectNamePrefix, String delimiter)
      throws IOException {
    log.debug("listObjectNames(%s, %s, %s)", bucketName, objectNamePrefix, delimiter);
    return Lists.transform(
        listObjectInfo(bucketName, objectNamePrefix, delimiter),
        ITEM_INFO_TO_NAME);
  }

  /**
   * Uses shared prefix-matching logic to filter entries from the metadata cache. For implicit
   * prefix matches with no corresponding real directory object, adds a fake directory object
   * with creationTime == 0.
   */
  @Override
  public List<GoogleCloudStorageItemInfo> listObjectInfo(
      final String bucketName, String objectNamePrefix, String delimiter)
      throws IOException {
    log.debug("listObjectInfo(%s, %s, %s)", bucketName, objectNamePrefix, delimiter);
    List<GoogleCloudStorageItemInfo> allObjectInfos = new ArrayList<>();
    Set<String> retrievedNames = new HashSet<>();
    Set<String> prefixes = new HashSet<>();
    List<CacheEntry> cachedObjects = resourceCache.getObjectList(
        bucketName, objectNamePrefix, delimiter, prefixes);
    if (cachedObjects != null) {
      // Pull the itemInfos out of all the matched entries; in our usage here of DirectoryListCache,
      // we expect the info to *always* be available.
      for (CacheEntry entry : cachedObjects) {
        GoogleCloudStorageItemInfo info = entry.getItemInfo();
        Preconditions.checkState(
            info != null, "Cache entry missing info for name '%s'!", entry.getResourceId());
        allObjectInfos.add(info);
        retrievedNames.add(entry.getResourceId().getObjectName());
      }

      // Check whether each raw prefix already had a valid real entry; if not, we'll add a fake
      // directory object, but we'll keep track of it.
      for (String prefix : prefixes) {
        if (!retrievedNames.contains(prefix)) {
          log.debug("Found implicit directory '%s'. Adding fake entry for it.", prefix);
          GoogleCloudStorageItemInfo fakeInfo = new GoogleCloudStorageItemInfo(
              new StorageResourceId(bucketName, prefix), 0, 0, null, null);
          allObjectInfos.add(fakeInfo);
          retrievedNames.add(prefix);

          // Also add a concrete object for each implicit directory, since the caller may decide
          // to call getItemInfo sometime later after listing it.
          resourceCache.putResourceId(fakeInfo.getResourceId()).setItemInfo(fakeInfo);
        }
      }
    }
    return allObjectInfos;
  }

  /**
   * Pure fetch from cache.
   */
  @Override
  public List<GoogleCloudStorageItemInfo> getItemInfos(List<StorageResourceId> resourceIds)
      throws IOException {
    log.debug("getItemInfos(%s)", resourceIds.toString());
    List<GoogleCloudStorageItemInfo> infos = new ArrayList<>();
    for (StorageResourceId resourceId : resourceIds) {
      infos.add(getItemInfo(resourceId));
    }
    return infos;
  }

  @Override
  public List<GoogleCloudStorageItemInfo> updateItems(List<UpdatableItemInfo> itemInfoList)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  /**
   * Pure fetch from cache.
   */
  @Override
  public GoogleCloudStorageItemInfo getItemInfo(StorageResourceId resourceId)
      throws IOException {
    log.debug("getItemInfo(%s)", resourceId);
    CacheEntry entry = resourceCache.getCacheEntry(resourceId);
    if (entry == null) {
      // TODO(user): Move the createItemInfoForNotFound method into GoogleCloudStorageItemInfo.
      return GoogleCloudStorageImpl.createItemInfoForNotFound(resourceId);
    } else {
      GoogleCloudStorageItemInfo info = entry.getItemInfo();
      Preconditions.checkState(
          info != null, "Cache entry missing info for name '%s'!", entry.getResourceId());
      return info;
    }
  }

  @Override
  public void close() {
    log.debug("close()");
  }

  @Override
  public void waitForBucketEmpty(String bucketName)
      throws IOException {
    throw new UnsupportedOperationException();
  }
}

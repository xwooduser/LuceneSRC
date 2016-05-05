/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.document;

import java.io.IOException;
import java.util.BitSet;

import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.PointsFormat;
import org.apache.lucene.codecs.PointsReader;
import org.apache.lucene.codecs.PointsWriter;
import org.apache.lucene.codecs.lucene60.Lucene60PointsReader;
import org.apache.lucene.codecs.lucene60.Lucene60PointsWriter;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.spatial.util.GeoRect;
import org.apache.lucene.spatial.util.GeoUtils;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.SloppyMath;
import org.apache.lucene.util.TestUtil;
import org.apache.lucene.util.bkd.BKDWriter;

/** Simple tests for {@link LatLonPoint#newDistanceQuery} */
public class TestLatLonPointDistanceQuery extends LuceneTestCase {

  /** test we can search for a point */
  public void testBasics() throws Exception {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);

    // add a doc with a location
    Document document = new Document();
    document.add(new LatLonPoint("field", 18.313694, -65.227444));
    writer.addDocument(document);
    
    // search within 50km and verify we found our doc
    IndexReader reader = writer.getReader();
    IndexSearcher searcher = newSearcher(reader, false);
    assertEquals(1, searcher.count(LatLonPoint.newDistanceQuery("field", 18, -65, 50_000)));

    reader.close();
    writer.close();
    dir.close();
  }
  
  /** negative distance queries are not allowed */
  public void testNegativeRadius() {
    IllegalArgumentException expected = expectThrows(IllegalArgumentException.class, () -> {
      LatLonPoint.newDistanceQuery("field", 18, 19, -1);
    });
    assertTrue(expected.getMessage().contains("radiusMeters"));
    assertTrue(expected.getMessage().contains("is invalid"));
  }
  
  /** NaN distance queries are not allowed */
  public void testNaNRadius() {
    IllegalArgumentException expected = expectThrows(IllegalArgumentException.class, () -> {
      LatLonPoint.newDistanceQuery("field", 18, 19, Double.NaN);
    });
    assertTrue(expected.getMessage().contains("radiusMeters"));
    assertTrue(expected.getMessage().contains("is invalid"));
  }
  
  /** Inf distance queries are not allowed */
  public void testInfRadius() {
    IllegalArgumentException expected;
    
    expected = expectThrows(IllegalArgumentException.class, () -> {
      LatLonPoint.newDistanceQuery("field", 18, 19, Double.POSITIVE_INFINITY);
    });
    assertTrue(expected.getMessage().contains("radiusMeters"));
    assertTrue(expected.getMessage().contains("is invalid"));
    
    expected = expectThrows(IllegalArgumentException.class, () -> {
      LatLonPoint.newDistanceQuery("field", 18, 19, Double.NEGATIVE_INFINITY);
    });
    assertTrue(expected.getMessage().contains("radiusMeters"));
    assertTrue(expected.getMessage().contains("is invalid"));
  }
  
  /** Run a few iterations with just 10 docs, hopefully easy to debug */
  public void testRandom() throws Exception {
    for (int iters = 0; iters < 100; iters++) {
      doRandomTest(10, 100);
    }
  }
  
  /** Runs with thousands of docs */
  @Nightly
  public void testRandomHuge() throws Exception {
    for (int iters = 0; iters < 10; iters++) {
      doRandomTest(2000, 100);
    }
  }
  
  private void doRandomTest(int numDocs, int numQueries) throws IOException {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = newIndexWriterConfig();
    int pointsInLeaf = 2 + random().nextInt(4);
    iwc.setCodec(new FilterCodec("Lucene60", TestUtil.getDefaultCodec()) {
      @Override
      public PointsFormat pointsFormat() {
        return new PointsFormat() {
          @Override
          public PointsWriter fieldsWriter(SegmentWriteState writeState) throws IOException {
            return new Lucene60PointsWriter(writeState, pointsInLeaf, BKDWriter.DEFAULT_MAX_MB_SORT_IN_HEAP);
          }

          @Override
          public PointsReader fieldsReader(SegmentReadState readState) throws IOException {
            return new Lucene60PointsReader(readState);
          }
        };
      }
    });
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir, iwc);

    for (int i = 0; i < numDocs; i++) {
      double latRaw = -90 + 180.0 * random().nextDouble();
      double lonRaw = -180 + 360.0 * random().nextDouble();
      // pre-normalize up front, so we can just use quantized value for testing and do simple exact comparisons
      double lat = LatLonPoint.decodeLatitude(LatLonPoint.encodeLatitude(latRaw));
      double lon = LatLonPoint.decodeLongitude(LatLonPoint.encodeLongitude(lonRaw));
      Document doc = new Document();
      doc.add(new LatLonPoint("field", lat, lon));
      doc.add(new StoredField("lat", lat));
      doc.add(new StoredField("lon", lon));
      writer.addDocument(doc);
    }
    IndexReader reader = writer.getReader();
    IndexSearcher searcher = new IndexSearcher(reader);

    for (int i = 0; i < numQueries; i++) {
      double lat = -90 + 180.0 * random().nextDouble();
      double lon = -180 + 360.0 * random().nextDouble();
      double radius = 50000000 * random().nextDouble();

      BitSet expected = new BitSet();
      for (int doc = 0; doc < reader.maxDoc(); doc++) {
        double docLatitude = reader.document(doc).getField("lat").numericValue().doubleValue();
        double docLongitude = reader.document(doc).getField("lon").numericValue().doubleValue();
        double distance = SloppyMath.haversinMeters(lat, lon, docLatitude, docLongitude);
        if (distance <= radius) {
          expected.set(doc);
        }
      }

      TopDocs topDocs = searcher.search(LatLonPoint.newDistanceQuery("field", lat, lon, radius), reader.maxDoc(), Sort.INDEXORDER);
      BitSet actual = new BitSet();
      for (ScoreDoc doc : topDocs.scoreDocs) {
        actual.set(doc.doc);
      }

      try {
        assertEquals(expected, actual);
      } catch (AssertionError e) {
        for (int doc = 0; doc < reader.maxDoc(); doc++) {
          double docLatitude = reader.document(doc).getField("lat").numericValue().doubleValue();
          double docLongitude = reader.document(doc).getField("lon").numericValue().doubleValue();
          double distance = SloppyMath.haversinMeters(lat, lon, docLatitude, docLongitude);
          System.out.println("" + doc + ": (" + docLatitude + "," + docLongitude + "), distance=" + distance);
        }
        throw e;
      }
    }
    reader.close();
    writer.close();
    dir.close();
  }
  
  public void testBoundingBoxOpto() {
    for (int i = 0; i < 1000; i++) {
      double lat = -90 + 180.0 * random().nextDouble();
      double lon = -180 + 360.0 * random().nextDouble();
      double radius = 50000000 * random().nextDouble();
      GeoRect box = GeoUtils.circleToBBox(lat, lon, radius);
      final GeoRect box1;
      final GeoRect box2;
      if (box.crossesDateline()) {
        box1 = new GeoRect(box.minLat, box.maxLat, -180, box.maxLon);
        box2 = new GeoRect(box.minLat, box.maxLat, box.minLon, 180);
      } else {
        box1 = box;
        box2 = null;
      }
      
      for (int j = 0; j < 10000; j++) {
        double lat2 = -90 + 180.0 * random().nextDouble();
        double lon2 = -180 + 360.0 * random().nextDouble();
        // if the point is within radius, then it should be in our bounding box
        if (SloppyMath.haversinMeters(lat, lon, lat2, lon2) <= radius) {
          assertTrue(lat >= box.minLat && lat <= box.maxLat);
          assertTrue(lon >= box1.minLon && lon <= box1.maxLon || (box2 != null && lon >= box2.minLon && lon <= box2.maxLon));
        }
      }
    }
  }
  
  public void testHaversinOpto() {
    for (int i = 0; i < 1000; i++) {
      double lat = -90 + 180.0 * random().nextDouble();
      double lon = -180 + 360.0 * random().nextDouble();
      double radius = 50000000 * random().nextDouble();
      GeoRect box = GeoUtils.circleToBBox(lat, lon, radius);

      if (box.maxLon - lon < 90 && lon - box.minLon < 90) {
        double minPartialDistance = Math.max(SloppyMath.haversinSortKey(lat, lon, lat, box.maxLon),
                                             SloppyMath.haversinSortKey(lat, lon, box.maxLat, lon));
      
        for (int j = 0; j < 10000; j++) {
          double lat2 = -90 + 180.0 * random().nextDouble();
          double lon2 = -180 + 360.0 * random().nextDouble();
          // if the point is within radius, then it should be <= our sort key
          if (SloppyMath.haversinMeters(lat, lon, lat2, lon2) <= radius) {
            assertTrue(SloppyMath.haversinSortKey(lat, lon, lat2, lon2) <= minPartialDistance);
          }
        }
      }
    }
  }
}

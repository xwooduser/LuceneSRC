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

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.PointValues.IntersectVisitor;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.spatial.util.GeoRect;
import org.apache.lucene.spatial.util.GeoUtils;
import org.apache.lucene.util.DocIdSetBuilder;
import org.apache.lucene.util.SloppyMath;

/**
 * Distance query for {@link LatLonPoint}.
 */
final class LatLonPointDistanceQuery extends Query {
  final String field;
  final double latitude;
  final double longitude;
  final double radiusMeters;

  public LatLonPointDistanceQuery(String field, double latitude, double longitude, double radiusMeters) {
    if (field == null) {
      throw new IllegalArgumentException("field cannot be null");
    }
    if (Double.isFinite(radiusMeters) == false || radiusMeters < 0) {
      throw new IllegalArgumentException("radiusMeters: '" + radiusMeters + "' is invalid");
    }
    GeoUtils.checkLatitude(latitude);
    GeoUtils.checkLongitude(longitude);
    this.field = field;
    this.latitude = latitude;
    this.longitude = longitude;
    this.radiusMeters = radiusMeters;
  }

  @Override
  public Weight createWeight(IndexSearcher searcher, boolean needsScores) throws IOException {
    GeoRect box = GeoUtils.circleToBBox(latitude, longitude, radiusMeters);
    final GeoRect box1;
    final GeoRect box2;

    // crosses dateline: split
    if (box.crossesDateline()) {
      box1 = new GeoRect(box.minLat, box.maxLat, -180.0, box.maxLon);
      box2 = new GeoRect(box.minLat, box.maxLat, box.minLon, 180.0);
    } else {
      box1 = box;
      box2 = null;
    }

    return new ConstantScoreWeight(this) {

      @Override
      public Scorer scorer(LeafReaderContext context) throws IOException {
        LeafReader reader = context.reader();
        PointValues values = reader.getPointValues();
        if (values == null) {
          // No docs in this segment had any points fields
          return null;
        }
        FieldInfo fieldInfo = reader.getFieldInfos().fieldInfo(field);
        if (fieldInfo == null) {
          // No docs in this segment indexed this field at all
          return null;
        }
        LatLonPoint.checkCompatible(fieldInfo);
        
        DocIdSetBuilder result = new DocIdSetBuilder(reader.maxDoc());
        values.intersect(field,
                         new IntersectVisitor() {
                           @Override
                           public void visit(int docID) {
                             result.add(docID);
                           }

                           @Override
                           public void visit(int docID, byte[] packedValue) {
                             assert packedValue.length == 8;
                             double lat = LatLonPoint.decodeLatitude(packedValue, 0);
                             double lon = LatLonPoint.decodeLongitude(packedValue, Integer.BYTES);
                             if (SloppyMath.haversinMeters(latitude, longitude, lat, lon) <= radiusMeters) {
                               visit(docID);
                             }
                           }
                           
                           // algorithm: we create a bounding box (two bounding boxes if we cross the dateline).
                           // 1. check our bounding box(es) first. if the subtree is entirely outside of those, bail.
                           // 2. see if the subtree is fully contained. if the subtree is enormous along the x axis, wrapping half way around the world, etc: then this can't work, just go to step 3.
                           // 3. recurse naively.
                           @Override
                           public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
                             double latMin = LatLonPoint.decodeLatitude(minPackedValue, 0);
                             double lonMin = LatLonPoint.decodeLongitude(minPackedValue, Integer.BYTES);
                             double latMax = LatLonPoint.decodeLatitude(maxPackedValue, 0);
                             double lonMax = LatLonPoint.decodeLongitude(maxPackedValue, Integer.BYTES);
                             
                             if ((latMax < box1.minLat || lonMax < box1.minLon || latMin > box1.maxLat || lonMin > box1.maxLon) && 
                                 (box2 == null || latMax < box2.minLat || lonMax < box2.minLon || latMin > box2.maxLat || lonMin > box2.maxLon)) {
                               // we are fully outside of bounding box(es), don't proceed any further.
                               return Relation.CELL_OUTSIDE_QUERY;
                             } else if (lonMax - longitude < 90 && longitude - lonMin < 90 &&
                                 SloppyMath.haversinMeters(latitude, longitude, latMin, lonMin) <= radiusMeters &&
                                 SloppyMath.haversinMeters(latitude, longitude, latMin, lonMax) <= radiusMeters &&
                                 SloppyMath.haversinMeters(latitude, longitude, latMax, lonMin) <= radiusMeters &&
                                 SloppyMath.haversinMeters(latitude, longitude, latMax, lonMax) <= radiusMeters) {
                               // we are fully enclosed, collect everything within this subtree
                               return Relation.CELL_INSIDE_QUERY;
                             } else {
                               // recurse: its inside our bounding box(es), but not fully, or it wraps around.
                               return Relation.CELL_CROSSES_QUERY;
                             }
                           }
                         });

        return new ConstantScoreScorer(this, score(), result.build().iterator());
      }
    };
  }

  public String getField() {
    return field;
  }

  public double getLatitude() {
    return latitude;
  }

  public double getLongitude() {
    return longitude;
  }

  public double getRadiusMeters() {
    return radiusMeters;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = super.hashCode();
    result = prime * result + field.hashCode();
    long temp;
    temp = Double.doubleToLongBits(latitude);
    result = prime * result + (int) (temp ^ (temp >>> 32));
    temp = Double.doubleToLongBits(longitude);
    result = prime * result + (int) (temp ^ (temp >>> 32));
    temp = Double.doubleToLongBits(radiusMeters);
    result = prime * result + (int) (temp ^ (temp >>> 32));
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (!super.equals(obj)) return false;
    if (getClass() != obj.getClass()) return false;
    LatLonPointDistanceQuery other = (LatLonPointDistanceQuery) obj;
    if (field.equals(other.field) == false) return false;
    if (Double.doubleToLongBits(latitude) != Double.doubleToLongBits(other.latitude)) return false;
    if (Double.doubleToLongBits(longitude) != Double.doubleToLongBits(other.longitude)) return false;
    if (Double.doubleToLongBits(radiusMeters) != Double.doubleToLongBits(other.radiusMeters)) return false;
    return true;
  }

  @Override
  public String toString(String field) {
    StringBuilder sb = new StringBuilder();
    if (!this.field.equals(field)) {
      sb.append(this.field);
      sb.append(':');
    }
    sb.append(latitude);
    sb.append(",");
    sb.append(longitude);
    sb.append(" +/- ");
    sb.append(radiusMeters);
    sb.append(" meters");
    return sb.toString();
  }
}

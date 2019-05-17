/*
 * Copyright (c) 2011-2019, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package boofcv.alg.feature.detect.line;


import boofcv.abst.feature.detect.extract.NonMaxSuppression;
import boofcv.alg.InputSanityCheck;
import boofcv.alg.feature.detect.peak.MeanShiftPeak;
import boofcv.alg.misc.ImageMiscOps;
import boofcv.alg.weights.WeightPixelGaussian_F32;
import boofcv.struct.QueueCorner;
import boofcv.struct.border.BorderType;
import boofcv.struct.image.*;
import georegression.struct.line.LineParametric2D_F32;
import georegression.struct.point.Point2D_I16;
import org.ddogleg.struct.FastQueue;
import org.ddogleg.struct.GrowQueue_F32;

/**
 * <p>
 * Hough transform based line detector.  Lines are parameterized based upon the (x,y) coordinate
 * of the closest point to the origin.  This parametrization is more convenient since it corresponds
 * directly with the image space.  See [1] for more details.
 * </p>
 *
 * <p>
 * The line's direction is estimated using the gradient at each point flagged as belonging to a line.
 * To minimize error the image center is used as the coordinate system's center.  However lines which
 * lie too close to the center can't have their orientation accurately estimated.
 * </p>
 *
 * <p>
 * [1] Section 9.3 of E.R. Davies, "Machine Vision Theory Algorithms Practicalities," 3rd Ed. 2005
 * </p>
 *
 * @author Peter Abeles
 */
public class HoughTransformLineFootOfNorm {

	// extracts line from the transform
	NonMaxSuppression extractor;
	// stores returned lines
	FastQueue<LineParametric2D_F32> lines = new FastQueue<>(10, LineParametric2D_F32.class, true);
	// lines are ignored if they are less than this distance from the origin
	// because the orientation can't be accurately estimated
	int minDistanceFromOrigin;
	// origin of the transform coordinate system
	int originX;
	int originY;
	// contains a set of counts for detected lines in each pixel
	// floating point image used because that's what FeatureExtractor's take as input
	GrayF32 transform = new GrayF32(1,1);
	// found lines in transform space
	QueueCorner foundLines = new QueueCorner(10);
	// list of points in the transform with non-zero values
	QueueCorner candidates = new QueueCorner(10);
	// line intensities for later pruning
	GrowQueue_F32 foundIntensity = new GrowQueue_F32(10);

	// Refine lines using mean shift. If radius <= 0 it won't be used
	MeanShiftPeak<GrayF32> refine = new MeanShiftPeak<>(10,0.001f,
			new WeightPixelGaussian_F32(),GrayF32.class, BorderType.ZERO);

	/**
	 * Specifies parameters of transform.
	 *
	 * @param extractor Extracts local maxima from transform space.  A set of candidates is provided, but can be ignored.
	 * @param minDistanceFromOrigin Distance from the origin in which lines will not be estimated.  In transform space.  Try 5.
	 */
	public HoughTransformLineFootOfNorm(NonMaxSuppression extractor,
										int minDistanceFromOrigin) {
		this.extractor = extractor;
		this.minDistanceFromOrigin = minDistanceFromOrigin;
		refine.setImage(transform);
		refine.setRadius(3);
	}

	/**
	 * Computes the Hough transform using the image gradient and a binary image which flags pixels as being edges or not.
	 *
	 * @param derivX Image derivative along x-axis.
	 * @param derivY Image derivative along y-axis.
	 * @param binary Non-zero pixels are considered to be line pixels.
	 */
	public <D extends ImageGray<D>> void transform(D derivX , D derivY , GrayU8 binary )
	{
		InputSanityCheck.checkSameShape(derivX,derivY,binary);

		transform.reshape(derivX.width,derivY.height);
		ImageMiscOps.fill(transform,0);

		originX = derivX.width/2;
		originY = derivX.height/2;
		candidates.reset();

		if( derivX instanceof GrayF32)
			_transform((GrayF32)derivX,(GrayF32)derivY,binary);
		else if( derivX instanceof GrayS16)
			_transform((GrayS16)derivX,(GrayS16)derivY,binary);
		else if( derivX instanceof GrayS32)
			_transform((GrayS32)derivX,(GrayS32)derivY,binary);
		else
			throw new IllegalArgumentException("Unsupported derivative image type: "+derivX.getClass().getSimpleName());
	}

	/**
	 * Searches for local maximas and converts into lines.
	 *
	 * @return Found lines in the image.
	 */
	public FastQueue<LineParametric2D_F32> extractLines() {
		lines.reset();
		foundLines.reset();
		foundIntensity.reset();

		extractor.process(transform,null, candidates,null, foundLines);

		for( int i = 0; i < foundLines.size(); i++ ) {
			Point2D_I16 p = foundLines.get(i);

			int x0 = p.x - originX;
			int y0 = p.y - originY;

			if( Math.abs(x0) >= minDistanceFromOrigin ||
					Math.abs(y0) >= minDistanceFromOrigin ) {
				LineParametric2D_F32 l = lines.grow();
				l.p.set(p.x,p.y);
				refine.search(l.p.x,l.p.y);
				// check for divergence
				if( l.p.distance(refine.getPeakX(),refine.getPeakY()) < refine.getRadius()*2 ) {
					l.p.set(refine.getPeakX(),refine.getPeakY());
				}
				l.slope.x = -(l.p.y-originY);
				l.slope.y = l.p.x-originX;
				foundIntensity.push(transform.get(p.x,p.y));
			}
		}

		return lines;
	}

	/**
	 * Takes the detected point along the line and its gradient and converts it into transform space.
	 * @param x point in image.
	 * @param y point in image.
	 * @param derivX gradient of point.
	 * @param derivY gradient of point.
	 */
	public void parameterize( int x , int y , float derivX , float derivY )
	{
		// put the point in a new coordinate system centered at the image's origin
		// this minimizes error, which is a function of distance from origin
		x -= originX;
		y -= originY;

		float v = (x*derivX + y*derivY)/(derivX*derivX + derivY*derivY);
		float vx = v*derivX + originX;
		float vy = v*derivY + originY;

		// finds the foot a line normal equation and put the point into image coordinate
		int x0 = (int)vx;
		int y0 = (int)vy;

		// weights for bilinear interpolate type weightings
		float wx = vx-x0;
		float wy = vy-y0;

		// make a soft decision and spread counts across neighbors
		addParameters(x0,y0, (1f-wx)*(1f-wy));
		addParameters(x0+1,y0, (wx)*(1f-wy));
		addParameters(x0,y0+1, (1f-wx)*(wy));
		addParameters(x0+1,y0+1, (wx)*(wy));
	}

	private void addParameters( int x , int y , float amount ) {
		if( transform.isInBounds(x,y)) {
			int index = transform.startIndex+y*transform.stride+x;
			// keep track of candidate pixels so that a sparse search can be done
			// to detect lines
			if( transform.data[index] == 0 )
				candidates.add(x,y);
			transform.data[index] += amount;
		}
	}

	/**
	 * Returns the Hough transform image.
	 *
	 * @return Transform image.
	 */
	public GrayF32 getTransform() {
		return transform;
	}

	public FastQueue<LineParametric2D_F32> getLines() {
		return lines;
	}

	/**
	 * Returns the intensity/edge count for each returned line.  Useful when doing
	 * post processing pruning.
	 *
	 * @return Array containing line intensities.
	 */
	public float[] getFoundIntensity() {
		return foundIntensity.data;
	}

	private void _transform(GrayF32 derivX , GrayF32 derivY , GrayU8 binary )
	{
		// apply the transform to the entire image
		for( int y = 0; y < binary.height; y++ ) {
			int start = binary.startIndex + y*binary.stride;
			int end = start + binary.width;

			for( int index = start; index < end; index++ ) {
				if( binary.data[index] != 0 ) {
					int x = index-start;
					parameterize(x,y,derivX.unsafe_get(x,y),derivY.unsafe_get(x,y));
				}
			}
		}
	}

	private void _transform(GrayS16 derivX , GrayS16 derivY , GrayU8 binary )
	{
		// apply the transform to the entire image
		for( int y = 0; y < binary.height; y++ ) {
			int start = binary.startIndex + y*binary.stride;
			int end = start + binary.width;

			for( int index = start; index < end; index++ ) {
				if( binary.data[index] != 0 ) {
					int x = index-start;
					parameterize(x,y,derivX.unsafe_get(x,y),derivY.unsafe_get(x,y));
				}
			}
		}
	}

	private void _transform(GrayS32 derivX , GrayS32 derivY , GrayU8 binary )
	{
		// apply the transform to the entire image
		for( int y = 0; y < binary.height; y++ ) {
			int start = binary.startIndex + y*binary.stride;
			int end = start + binary.width;

			for( int index = start; index < end; index++ ) {
				if( binary.data[index] != 0 ) {
					int x = index-start;
					parameterize(x,y,derivX.unsafe_get(x,y),derivY.unsafe_get(x,y));
				}
			}
		}
	}

	public void setRefineRadius( int radius ) {
		refine.setRadius(radius);
	}

	public int getRefineRadius() {
		return refine.getRadius();
	}
}

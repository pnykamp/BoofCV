/*
 * Copyright (c) 2011-2018, Peter Abeles. All Rights Reserved.
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

package boofcv.factory.geo;

import boofcv.abst.geo.*;
import boofcv.abst.geo.bundle.BundleAdjustment;
import boofcv.abst.geo.bundle.BundleAdjustmentSchur_DSCC;
import boofcv.abst.geo.bundle.SceneStructureMetric;
import boofcv.abst.geo.bundle.SceneStructureProjective;
import boofcv.abst.geo.f.*;
import boofcv.abst.geo.h.HomographyDLT_to_Epipolar;
import boofcv.abst.geo.h.HomographyTLS_to_Epipolar;
import boofcv.abst.geo.h.LeastSquaresHomography;
import boofcv.abst.geo.pose.*;
import boofcv.abst.geo.triangulate.*;
import boofcv.abst.geo.trifocal.WrapRefineThreeViewProjectiveGeometric;
import boofcv.abst.geo.trifocal.WrapTrifocalAlgebraicPoint7;
import boofcv.abst.geo.trifocal.WrapTrifocalLinearPoint7;
import boofcv.alg.geo.ModelObservationResidualN;
import boofcv.alg.geo.bundle.*;
import boofcv.alg.geo.f.DistanceEpipolarConstraint;
import boofcv.alg.geo.h.HomographyDirectLinearTransform;
import boofcv.alg.geo.h.HomographyResidualSampson;
import boofcv.alg.geo.h.HomographyResidualTransfer;
import boofcv.alg.geo.h.HomographyTotalLeastSquares;
import boofcv.alg.geo.pose.P3PFinsterwalder;
import boofcv.alg.geo.pose.P3PGrunert;
import boofcv.alg.geo.pose.PnPLepetitEPnP;
import boofcv.alg.geo.pose.PoseFromPairLinear6;
import boofcv.alg.geo.triangulate.*;
import boofcv.alg.geo.trifocal.RefineThreeViewProjectiveGeometric;
import boofcv.alg.geo.trifocal.TrifocalAlgebraicPoint7;
import boofcv.misc.ConfigConverge;
import boofcv.struct.geo.AssociatedPair;
import georegression.fitting.MotionTransformPoint;
import georegression.fitting.se.FitSpecialEuclideanOps_F64;
import georegression.struct.point.Point3D_F64;
import georegression.struct.se.Se3_F64;
import org.ddogleg.optimization.FactoryOptimization;
import org.ddogleg.optimization.FactoryOptimizationSparse;
import org.ddogleg.optimization.UnconstrainedLeastSquares;
import org.ddogleg.optimization.UnconstrainedLeastSquaresSchur;
import org.ddogleg.optimization.lm.ConfigLevenbergMarquardt;
import org.ddogleg.optimization.trustregion.ConfigTrustRegion;
import org.ddogleg.solver.PolynomialOps;
import org.ddogleg.solver.RootFinderType;
import org.ddogleg.struct.FastQueue;
import org.ejml.data.DMatrixSparseCSC;

import javax.annotation.Nullable;

/**
 * Factory for creating abstracted algorithms related to multi-view geometry
 *
 * @author Peter Abeles
 */
public class FactoryMultiView {

	public static BundleAdjustment<SceneStructureMetric> bundleAdjustmentMetric( @Nullable ConfigBundleAdjustment config ) {
		if( config == null )
			config = new ConfigBundleAdjustment();

		UnconstrainedLeastSquaresSchur<DMatrixSparseCSC> minimizer;

		if( config.configOptimizer instanceof ConfigTrustRegion )
			minimizer = FactoryOptimizationSparse.doglegSchur((ConfigTrustRegion)config.configOptimizer);
		else
			minimizer = FactoryOptimizationSparse.levenbergMarquardtSchur((ConfigLevenbergMarquardt)config.configOptimizer);

		return new BundleAdjustmentSchur_DSCC<>(minimizer,
				new BundleAdjustmentMetricResidualFunction(),
				new BundleAdjustmentMetricSchurJacobian_DSCC(),
				new CodecSceneStructureMetric());
	}

	public static BundleAdjustment<SceneStructureProjective> bundleAdjustmentProjective( @Nullable ConfigBundleAdjustment config ) {
		if( config == null )
			config = new ConfigBundleAdjustment();

		UnconstrainedLeastSquaresSchur<DMatrixSparseCSC> minimizer;

		if( config.configOptimizer instanceof ConfigTrustRegion )
			minimizer = FactoryOptimizationSparse.doglegSchur((ConfigTrustRegion)config.configOptimizer);
		else
			minimizer = FactoryOptimizationSparse.levenbergMarquardtSchur((ConfigLevenbergMarquardt)config.configOptimizer);


		return new BundleAdjustmentSchur_DSCC<>(minimizer,
				new BundleAdjustmentProjectiveResidualFunction(),
				new BundleAdjustmentProjectiveSchurJacobian_DSCC(),
				new CodecSceneStructureProjective());
	}

	/**
	 * Returns an algorithm for estimating a homography matrix given a set of {@link AssociatedPair}.
	 *
	 * @see HomographyDirectLinearTransform
	 *
	 * @param normalizeInput If input is in pixel coordinates set to true.  False if in normalized image coordinates.
	 * @return Homography estimator.
	 */
	public static Estimate1ofEpipolar homographyDLT(boolean normalizeInput ) {
		HomographyDirectLinearTransform alg = new HomographyDirectLinearTransform(normalizeInput);
		return new HomographyDLT_to_Epipolar(alg);
	}

	/**
	 * Returns an algorithm for estimating a homography matrix given a set of {@link AssociatedPair}.
	 *
	 * @see HomographyTotalLeastSquares
	 *
	 * @return Homography estimator.
	 */
	public static Estimate1ofEpipolar homographyTLS() {
		HomographyTotalLeastSquares alg = new HomographyTotalLeastSquares();
		return new HomographyTLS_to_Epipolar(alg);
	}


	/**
	 * Creates a non-linear optimizer for refining estimates of homography matrices.
	 *
	 * @see HomographyResidualSampson
	 * @see HomographyResidualTransfer
	 *
	 * @param tol Tolerance for convergence.  Try 1e-8
	 * @param maxIterations Maximum number of iterations it will perform.  Try 100 or more.
	 * @return Homography refinement
	 */
	public static RefineEpipolar homographyRefine(double tol , int maxIterations , EpipolarError type ) {
		ModelObservationResidualN residuals;
		switch( type ) {
			case SIMPLE:
				residuals = new HomographyResidualTransfer();
				break;

			case SAMPSON:
				residuals = new HomographyResidualSampson();
				break;

			default:
				throw new IllegalArgumentException("Type not supported: "+type);
		}

		return new LeastSquaresHomography(tol,maxIterations,residuals);
	}

	/**
	 * <p>
	 * Returns an algorithm for estimating a fundamental or essential matrix given a set of
	 * {@link AssociatedPair} in pixel coordinates.  The number of hypotheses returned and minimum number of samples
	 * is dependent on the implementation.  The ambiguity from multiple hypotheses can be resolved using other
	 * sample points and testing additional constraints.
	 * </p>
	 *
	 * <p>
	 * All estimated epipolar matrices will have the following constraint:<br>
	 * x'*F*x = 0, where F is the epipolar matrix, x' = currLoc, and x = keyLoc.
	 * </p>
	 *
	 * <p>
	 * There are more differences between these algorithms than the minimum number of sample points.  Consult
	 * the literature for information on critical surfaces which will work or not work with each algorithm.  In
	 * general, algorithm which require fewer samples have less issues with critical surfaces than the 8-point
	 * algorithm.
	 * </p>
	 *
	 * <p>
	 * IMPORTANT: When estimating a fundamental matrix use pixel coordinates.  When estimating an essential matrix
	 * use normalized image coordinates from a calibrated camera.
	 * </p>
	 *
	 * <p>
	 * IMPORTANT. The number of allowed sample points varies depending on the algorithm.  The 8 point algorithm can
	 * process 8 or more points.  Both the 5 an 7 point algorithms require exactly 5 and 7 points exactly. In addition
	 * the 5-point algorithm is only for the calibrated (essential) case.
	 * </p>
	 *
	 * @see boofcv.alg.geo.f.EssentialNister5
	 * @see boofcv.alg.geo.f.FundamentalLinear7
	 * @see boofcv.alg.geo.f.FundamentalLinear8
	 *
	 * @param which Specifies which algorithm is to be created
	 * @return Fundamental or essential estimation algorithm that returns multiple hypotheses.
	 */
	public static EstimateNofEpipolar fundamental_N(EnumFundamental which )
	{
		switch( which ) {
			case LINEAR_8:
				return new Estimate1toNofEpipolar(new WrapFundamentalLinear8(true));

			case LINEAR_7:
				return new WrapFundamentalLinear7(true);
		}

		throw new IllegalArgumentException("Unknown algorithm "+which);
	}

	public static EstimateNofEpipolar essential_N(EnumEssential which )
	{
		switch( which ) {
			case LINEAR_8:
				return new Estimate1toNofEpipolar(new WrapFundamentalLinear8(false));

			case LINEAR_7:
				return new WrapFundamentalLinear7(false);

			case NISTER_5:
				return new WrapEssentialNister5();
		}

		throw new IllegalArgumentException("Unknown algorithm "+which);
	}

	/**
	 * <p>
	 * Similar to {@link #fundamental_N}, but it returns only a single hypothesis.  If
	 * the underlying algorithm generates multiple hypotheses they are resolved by considering additional
	 * sample points. For example, if you are using the 7 point algorithm at least one additional sample point
	 * is required to resolve that ambiguity.  So 8 or more sample points are now required.
	 * </p>
	 *
	 * <p>
	 * All estimated epipolar matrices will have the following constraint:<br>
	 * x'*F*x = 0, where F is the epipolar matrix, x' = currLoc, and x = keyLoc.
	 * </p>
	 *
	 * <p>
	 * See {@link #fundamental_N} for a description of the algorithms and what 'minimumSamples'
	 * and 'isFundamental' do.
	 * </p>
	 *
	 * <p>
	 * The 8-point algorithm already returns a single hypothesis and ignores the 'numRemoveAmbiguity' parameter.
	 * All other algorithms require one or more points to remove ambiguity.  Understanding a bit of theory is required
	 * to understand what a good number of points is.  If a single point is used then to select the correct answer that
	 * point must be in the inlier set.  If more than one point, say 10, then not all of those points must be in the
	 * inlier set,
	 * </p>
	 *
	 * @see GeoModelEstimatorNto1
	 *
	 * @param which Specifies which algorithm is to be created
	 * @param numRemoveAmbiguity Number of sample points used to prune hypotheses. Ignored if only a single solution.
	 * @return Fundamental or essential estimation algorithm that returns a single hypothesis.
	 */
	public static Estimate1ofEpipolar fundamental_1(EnumFundamental which, int numRemoveAmbiguity)
	{
		switch( which ) {
			case LINEAR_8:
				return new WrapFundamentalLinear8(true);
		}

		if( numRemoveAmbiguity <= 0 )
			throw new IllegalArgumentException("numRemoveAmbiguity must be greater than zero");

		EstimateNofEpipolar alg = fundamental_N(which);
		DistanceEpipolarConstraint distance = new DistanceEpipolarConstraint();

		return new EstimateNto1ofEpipolar(alg,distance,numRemoveAmbiguity);
	}

	public static Estimate1ofEpipolar essential_1(EnumEssential which, int numRemoveAmbiguity)
	{
		switch( which ) {
			case LINEAR_8:
				return new WrapFundamentalLinear8(false);
		}

		if( numRemoveAmbiguity <= 0 )
			throw new IllegalArgumentException("numRemoveAmbiguity must be greater than zero");

		EstimateNofEpipolar alg = essential_N(which);
		DistanceEpipolarConstraint distance = new DistanceEpipolarConstraint();

		return new EstimateNto1ofEpipolar(alg,distance,numRemoveAmbiguity);
	}

	/**
	 * Creates a non-linear optimizer for refining estimates of fundamental or essential matrices.
	 *
	 * @see boofcv.alg.geo.f.FundamentalResidualSampson
	 * @see boofcv.alg.geo.f.FundamentalResidualSimple
	 *
	 * @param tol Tolerance for convergence.  Try 1e-8
	 * @param maxIterations Maximum number of iterations it will perform.  Try 100 or more.
	 * @return RefineEpipolar
	 */
	public static RefineEpipolar fundamentalRefine(double tol , int maxIterations , EpipolarError type )
	{
		switch( type ) {
			case SAMPSON:
				return new LeastSquaresFundamental(tol,maxIterations,true);
			case SIMPLE:
				return new LeastSquaresFundamental(tol,maxIterations,false);
		}

		throw new IllegalArgumentException("Type not supported: "+type);
	}

	/**
	 * Creates a trifocal tensor estimation algorithm.
	 *
	 * @param config configuration for the estimator
	 * @return Trifocal tensor estimator
	 */
	public static Estimate1ofTrifocalTensor trifocal_1( @Nullable ConfigTrifocal config ) {
		if( config == null ) {
			config = new ConfigTrifocal();
		}

		switch( config.which ) {
			case LINEAR_7:
				return new WrapTrifocalLinearPoint7();

			case ALGEBRAIC_7:
				ConfigConverge cc = config.converge;
				UnconstrainedLeastSquares optimizer = FactoryOptimization.levenbergMarquardt(null, false);
				TrifocalAlgebraicPoint7 alg = new TrifocalAlgebraicPoint7(optimizer,
						cc.maxIterations,cc.ftol,cc.gtol);

				return new WrapTrifocalAlgebraicPoint7(alg);
		}

		throw new IllegalArgumentException("Unknown type "+config.which);
	}

	/**
	 * Used to refine three projective views. This is the same as refining a trifocal tensor.
	 *
	 * @return RefineThreeViewProjective
	 */
	public static RefineThreeViewProjective threeViewRefine( @Nullable ConfigThreeViewRefine config ) {
		if( config == null )
			config = new ConfigThreeViewRefine();

		switch( config.which) {
			case GEOMETRIC:
				RefineThreeViewProjectiveGeometric alg = new RefineThreeViewProjectiveGeometric();
				alg.getConverge().set(config.convergence);
				alg.setScale(config.normalizePixels);
				return new WrapRefineThreeViewProjectiveGeometric(alg);
		}

		throw new IllegalArgumentException("Unknown algorithm "+config.which);
	}

	/**
	 * Creates an estimator for the PnP problem that uses only three observations, which is the minimal case
	 * and known as P3P.
	 *
	 * <p>NOTE: Observations are in normalized image coordinates NOT pixels.</p>
	 *
	 * @param which The algorithm which is to be returned.
	 * @param numIterations Number of iterations. Only used by some algorithms and recommended number varies
	 *                      significantly by algorithm.
	 * @return An estimator which can return multiple estimates.
	 */
	public static EstimateNofPnP pnp_N(EnumPNP which , int numIterations ) {

		MotionTransformPoint<Se3_F64, Point3D_F64> motionFit = FitSpecialEuclideanOps_F64.fitPoints3D();

		switch( which ) {
			case P3P_GRUNERT:
				P3PGrunert grunert = new P3PGrunert(PolynomialOps.createRootFinder(5, RootFinderType.STURM));
				return new WrapP3PLineDistance(grunert,motionFit);

			case P3P_FINSTERWALDER:
				P3PFinsterwalder finster = new P3PFinsterwalder(PolynomialOps.createRootFinder(4,RootFinderType.STURM));
				return new WrapP3PLineDistance(finster,motionFit);

			case EPNP:
				Estimate1ofPnP epnp = pnp_1(which,numIterations,0);
				return new Estimate1toNofPnP(epnp);

			case IPPE:
				Estimate1ofEpipolar H = FactoryMultiView.homographyTLS();
				return new Estimate1toNofPnP(new IPPE_to_EstimatePnP(H));
		}

		throw new IllegalArgumentException("Type "+which+" not known");
	}

	/**
	 * Created an estimator for the P3P problem that selects a single solution by considering additional
	 * observations.
	 *
	 * <p>NOTE: Observations are in normalized image coordinates NOT pixels.</p>
	 *
	 * <p>
	 * NOTE: EPnP has several tuning parameters and the defaults here might not be the best for your situation.
	 * Use {@link #computePnPwithEPnP} if you wish to have access to all parameters.
	 * </p>
	 *
	 * @param which The algorithm which is to be returned.
	 * @param numIterations Number of iterations. Only used by some algorithms and recommended number varies
	 *                      significantly by algorithm.
	 * @param numTest How many additional sample points are used to remove ambiguity in the solutions.  Not used
	 *                if only a single solution is found.
	 * @return An estimator which returns a single estimate.
	 */
	public static Estimate1ofPnP pnp_1(EnumPNP which, int numIterations , int numTest) {

		if( which == EnumPNP.EPNP ) {
			PnPLepetitEPnP alg = new PnPLepetitEPnP(0.1);
			alg.setNumIterations(numIterations);
			return new WrapPnPLepetitEPnP(alg);
		} else if( which == EnumPNP.IPPE ) {
			Estimate1ofEpipolar H = FactoryMultiView.homographyTLS();
			return new IPPE_to_EstimatePnP(H);
		}

		FastQueue<Se3_F64> solutions = new FastQueue<>(4, Se3_F64.class, true);

		return new EstimateNto1ofPnP(pnp_N(which,-1),solutions,numTest);
	}

	/**
	 * Returns a solution to the PnP problem for 4 or more points using EPnP. Fast and fairly
	 * accurate algorithm.  Can handle general and planar scenario automatically.
	 *
	 * <p>NOTE: Observations are in normalized image coordinates NOT pixels.</p>
	 *
	 * @see PnPLepetitEPnP
	 *
	 * @param numIterations If more then zero then non-linear optimization is done.  More is not always better.  Try 10
	 * @param magicNumber Affects how the problem is linearized.  See comments in {@link PnPLepetitEPnP}.  Try 0.1
	 * @return  Estimate1ofPnP
	 */
	public static Estimate1ofPnP computePnPwithEPnP(int numIterations, double magicNumber) {
		PnPLepetitEPnP alg = new PnPLepetitEPnP(magicNumber);
		alg.setNumIterations(numIterations);
		return new WrapPnPLepetitEPnP(alg);
	}

	/**
	 * Refines a pose solution to the PnP problem using non-linear least squares..
	 *
	 * @param tol Convergence tolerance. Try 1e-8
	 * @param maxIterations Maximum number of iterations.  Try 200
	 */
	public static RefinePnP pnpRefine(double tol , int maxIterations ) {
		return new PnPRefineRodrigues(tol,maxIterations);
	}

	/**
	 * Estimate the camera motion give two observations and the 3D world coordinate of each points.
	 *
	 * @return PoseFromPairLinear6
	 */
	public static PoseFromPairLinear6 triangulatePoseFromPair() {
		return new PoseFromPairLinear6();
	}

	/**
	 * Triangulate two view using the Discrete Linear Transform (DLT) with a calibrated camera.
	 *
	 * @see Wrap2ViewPixelDepthLinear
	 * @see TriangulateMetricLinearDLT
	 *
	 * @return Two view triangulation algorithm
	 */
	public static Triangulate2ViewsMetric triangulate2ViewMetric(@Nullable ConfigTriangulation config ) {
		if( config == null )
			config = new ConfigTriangulation();

		switch ( config.type ) {
			case DLT:
				return new Wrap2ViewPixelDepthLinear();

			case GEOMETRIC:
				return new Wrap2ViewsTriangulateGeometric();

		}
		throw new IllegalArgumentException("Unknown or unsupported type "+config.type);
	}

	/**
	 * Triangulate two view using the Discrete Linear Transform (DLT) with an uncalibrated camera.
	 *
	 * @see TriangulateProjectiveLinearDLT
	 *
	 * @return Two view triangulation algorithm
	 */
	public static Triangulate2ViewsProjective triangulate2ViewProjective(@Nullable ConfigTriangulation config ) {
		if( config == null )
			config = new ConfigTriangulation();

		switch( config.type ) {
			case DLT:
				return new Wrap2ViewsTriangulateProjectiveDLT();

		}
		throw new IllegalArgumentException("Unknown or unsupported type "+config.type);
	}

	/**
	 * Triangulate N views using the Discrete Linear Transform (DLT) with a calibrated camera
	 *
	 * @see TriangulateMetricLinearDLT
	 *
	 * @return Two view triangulation algorithm
	 */
	public static TriangulateNViewsMetric triangulateNViewCalibrated(@Nullable ConfigTriangulation config ) {
		if( config == null )
			config = new ConfigTriangulation();

		switch ( config.type ) {
			case DLT:
				return new WrapNViewsTriangulateMetricDLT();

			case GEOMETRIC: {
				TriangulateNViewsMetric estimator = new WrapNViewsTriangulateMetricDLT();
				TriangulateRefineMetricLS refiner = new TriangulateRefineMetricLS(config.optimization.gtol,config.optimization.maxIterations);
				return new TriangulateThenRefineMetric(estimator,refiner);
			}

		}
		throw new IllegalArgumentException("Unknown or unsupported type "+config.type);
	}

	/**
	 * Triangulate N views using the Discrete Linear Transform (DLT) with an uncalibrated camera
	 *
	 * @see TriangulateProjectiveLinearDLT
	 *
	 * @return Two view triangulation algorithm
	 */
	public static TriangulateNViewsProjective triangulateNView( @Nullable ConfigTriangulation config ) {
		if( config == null )
			config = new ConfigTriangulation();

		switch( config.type) {
			case DLT:
				return new WrapNViewsTriangulateProjectiveDLT();

			case ALGEBRAIC:
			case GEOMETRIC: {
				TriangulateNViewsProjective estimator = new WrapNViewsTriangulateProjectiveDLT();
				TriangulateRefineProjectiveLS refiner = new TriangulateRefineProjectiveLS(config.optimization.gtol,config.optimization.maxIterations);
				return new TriangulateThenRefineProjective(estimator,refiner);
			}
		}
		throw new IllegalArgumentException("Unknown or unsupported type "+config.type);
	}

	/**
	 * Refine the triangulation using Sampson error.  Approximately takes in account epipolar constraints.
	 *
	 * @see ResidualsTriangulateEpipolarSampson
	 *
	 * @param config Convergence criteria
	 * @return Triangulation refinement algorithm.
	 */
	public static RefineTriangulateEpipolar triangulateRefineEpipolar(ConfigConverge config ) {
		return new TriangulateRefineEpipolarLS(config.gtol,config.maxIterations);
	}

	/**
	 * Refine the triangulation by computing the difference between predicted and actual pixel location.
	 * Does not take in account epipolar constraints.
	 *
	 * @see ResidualsTriangulateMetricSimple
	 *
	 * @param config Convergence criteria
	 * @return Triangulation refinement algorithm.
	 */
	public static RefineTriangulateMetric triangulateRefineMetric(ConfigConverge config ) {
		return new TriangulateRefineMetricLS(config.gtol,config.maxIterations);
	}

	/**
	 * Refines a projective triangulation
	 *
	 * @see ResidualsTriangulateProjective
	 *
	 * @param config Convergence criteria
	 * @return Triangulation refinement algorithm.
	 */
	public static RefineTriangulateProjective triangulateRefineProj(ConfigConverge config ) {
		return new TriangulateRefineProjectiveLS(config.gtol,config.maxIterations);
	}
}

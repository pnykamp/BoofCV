/*
 * Copyright (c) 2011-2014, Peter Abeles. All Rights Reserved.
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

package boofcv.abst.flow;

import boofcv.factory.flow.FactoryDenseOpticalFlow;
import boofcv.struct.image.ImageFloat32;

/**
 * @author Peter Abeles
 */
public class TestBroxWarpingSpacial_to_DenseOpticalFlow extends GeneralDenseOpticalFlowChecks<ImageFloat32>{

	public TestBroxWarpingSpacial_to_DenseOpticalFlow() {
		super(ImageFloat32.class);
		justCorrectSign = true;
	}

	@Override
	public DenseOpticalFlow<ImageFloat32> createAlg(Class<ImageFloat32> imageType) {
		return FactoryDenseOpticalFlow.broxWarping(null, ImageFloat32.class);
	}
}
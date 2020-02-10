@file:Suppress("DEPRECATION")

package com.serenegiant.widget
/*
 * libcommon
 * utility/helper classes for myself
 *
 * Copyright (c) 2014-2020 saki t_saki@serenegiant.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
*/

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.Camera.CameraInfo
import android.os.Handler
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.View.OnLongClickListener
import android.view.WindowManager
import androidx.annotation.WorkerThread
import com.serenegiant.system.BuildCheck
import com.serenegiant.utils.HandlerThreadHandler
import java.io.IOException
import java.util.*
import java.util.concurrent.CopyOnWriteArraySet

/**
 * カメラプレビュー処理の委譲クラス
 */
class CameraDelegator(
	view: ICameraView,
	width: Int, height: Int,
	cameraRenderer: ICameraRenderer) {

	/**
	 * CameraDelegatorの親Viewがサポートしないといけないインターフェースメソッド
	 */
	interface ICameraView {
		// View
		fun setOnClickListener(listener: View.OnClickListener?)
		fun setOnLongClickListener(listener: OnLongClickListener?)
		fun getWidth(): Int
		fun getHeight(): Int
		fun post(task: Runnable): Boolean
		// GLSurfaceView
		fun onResume()
		fun onPause()
		fun queueEvent(task: Runnable)

		fun setVideoSize(width: Int, height: Int)

		fun addListener(listener: OnFrameAvailableListener)
		fun removeListener(listener: OnFrameAvailableListener)

		fun getScaleMode(): Int
		fun setScaleMode(mode: Int)

		fun getVideoWidth(): Int
		fun getVideoHeight(): Int

	}

	/**
	 * 映像が更新されたときの通知用コールバックリスナー
	 */
	interface OnFrameAvailableListener {
		fun onFrameAvailable()
	}

	/**
	 * カメラ映像をGLSurfaceViewへ描画するためのGLSurfaceView.Rendererインターフェース
	 */
	interface ICameraRenderer {
		fun hasSurface(): Boolean
		fun updateViewport()
		fun onPreviewSizeChanged(width: Int, height: Int)
		/**
		 * カメラ映像受け取り用のSurfaceTextureを取得
		 * @return
		 */
		fun getInputSurfaceTexture(): SurfaceTexture
	}

//--------------------------------------------------------------------------------

	private val mView: ICameraView
	private val mSync = Any()
	val cameraRenderer: ICameraRenderer
	private val mListeners: MutableSet<OnFrameAvailableListener> = CopyOnWriteArraySet()
	private var mCameraHandler: Handler? = null
	/**
	 * カメラ映像幅を取得
	 * @return
	 */
	var width: Int
		private set
	/**
	 * カメラ映像高さを取得
	 * @return
	 */
	var height: Int
		private set

	private var mRotation = 0
	private var mScaleMode = SCALE_STRETCH_FIT
	private var mCamera: Camera? = null
	@Volatile
	private var mResumed = false

	init {
		if (DEBUG) Log.v(TAG, String.format("コンストラクタ:(%dx%d)", width, height))
		mView = view
		@Suppress("LeakingThis")
		this.cameraRenderer = cameraRenderer
		this.width = width
		this.height = height
	}

	@Throws(Throwable::class)
	protected fun finalize() {
		release()
	}

	/**
	 * 関連するリソースを廃棄する
	 */
	fun release() {
		synchronized(mSync) {
			if (mCameraHandler != null) {
				if (DEBUG) Log.v(TAG, "release:")
				mCameraHandler!!.removeCallbacksAndMessages(null)
				mCameraHandler!!.looper.quit()
				mCameraHandler = null
			}
		}
	}

	/**
	 * GLSurfaceView#onResumeが呼ばれたときの処理
	 */
	fun onResume() {
		if (DEBUG) Log.v(TAG, "onResume:")
		mResumed = true
		if (cameraRenderer.hasSurface()) {
			if (mCameraHandler == null) {
				if (DEBUG) Log.v(TAG, "surface already exist")
				startPreview(mView.getWidth(), mView.getHeight())
			}
		}
	}

	/**
	 * GLSurfaceView#onPauseが呼ばれたときの処理
	 */
	fun onPause() {
		if (DEBUG) Log.v(TAG, "onPause:")
		mResumed = false
		// just request stop previewing
		stopPreview()
	}

	/**
	 * 映像が更新されたときのコールバックリスナーを登録
	 * @param listener
	 */
	fun addListener(listener: OnFrameAvailableListener?) {
		if (DEBUG) Log.v(TAG, "addListener:$listener")
		if (listener != null) {
			mListeners.add(listener)
		}
	}

	/**
	 * 映像が更新されたときのコールバックリスナーの登録を解除
	 * @param listener
	 */
	fun removeListener(listener: OnFrameAvailableListener) {
		if (DEBUG) Log.v(TAG, "removeListener:$listener")
		mListeners.remove(listener)
	}

	/**
	 * 映像が更新されたときのコールバックを呼び出す
	 */
	fun callOnFrameAvailable() {
		for (listener in mListeners) {
			try {
				listener.onFrameAvailable()
			} catch (e: Exception) {
				mListeners.remove(listener)
			}
		}
	}

	var scaleMode: Int
		/**
		 * 現在のスケールモードを取得
		 * @return
		 */
		get() {
			if (DEBUG) Log.v(TAG, "getScaleMode:$mScaleMode")
			return mScaleMode
		}
		/**
		 * スケールモードをセット
		 * @param mode
		 */
		set(mode) {
			if (DEBUG) Log.v(TAG, "setScaleMode:$mode")
			if (mScaleMode != mode) {
				mScaleMode = mode
				mView.queueEvent(Runnable {
					cameraRenderer.updateViewport()
				})
			}
		}

	/**
	 * カメラ映像サイズを変更要求
	 * @param width
	 * @param height
	 */
	fun setVideoSize(width: Int, height: Int) {
		if (DEBUG) Log.v(TAG, String.format("setVideoSize:(%dx%d)", width, height))
		if (mRotation % 180 == 0) {
			this.width = width
			this.height = height
		} else {
			this.width = height
			this.height = width
		}
		mView.queueEvent(Runnable {
			cameraRenderer.updateViewport()
		})
	}

	/**
	 * プレビュー開始
	 * @param width
	 * @param height
	 */
	fun startPreview(width: Int, height: Int) {
		if (DEBUG) Log.v(TAG, String.format("startPreview:(%dx%d)", width, height))
		synchronized(mSync) {
			if (mCameraHandler == null) {
				mCameraHandler = HandlerThreadHandler.createHandler("CameraHandler")
			}
			mCameraHandler!!.post {
				handleStartPreview(width, height)
			}
		}
	}

	/**
	 * プレビュー終了
	 */
	fun stopPreview() {
		if (DEBUG) Log.v(TAG, "stopPreview:$mCamera")
		synchronized(mSync) {
			if (mCamera != null) {
				mCamera!!.stopPreview()
				if (mCameraHandler != null) {
					mCameraHandler!!.post {
						handleStopPreview()
						release()
					}
				}
			}
		}
	}

//--------------------------------------------------------------------------------
	/**
	 * カメラプレビュー開始の実体
	 * @param width
	 * @param height
	 */
	@WorkerThread
	private fun handleStartPreview(width: Int, height: Int) {
		if (DEBUG) Log.v(TAG, "CameraThread#handleStartPreview:")
		var camera: Camera?
		synchronized(mSync) {
			camera = mCamera
		}
		if (camera == null) {
			// This is a sample project so just use 0 as camera ID.
			// it is better to selecting camera is available
			try {
				camera = Camera.open(CAMERA_ID)
				val params = camera!!.getParameters()
				if (params != null) {
					val focusModes = params.supportedFocusModes
					if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
						params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
					} else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
						params.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
					} else {
						if (DEBUG) Log.i(TAG, "handleStartPreview:Camera does not support autofocus")
					}
					// let's try fastest frame rate. You will get near 60fps, but your device become hot.
					val supportedFpsRange = params.supportedPreviewFpsRange
					val n = supportedFpsRange?.size ?: 0
					var max_fps: IntArray? = null
					for (i in n - 1 downTo 0) {
						val range = supportedFpsRange!![i]
						if (DEBUG) Log.v(TAG,
							String.format("handleStartPreview:supportedFpsRange(%d)=(%d,%d)",
								i, range[0], range[1]))
						if ((range[0] <= TARGET_FPS_MS) && (TARGET_FPS_MS <= range[1])) {
							max_fps = range
							break
						}
					}
					if (max_fps == null) { // 見つからなかったときは一番早いフレームレートを選択
						max_fps = supportedFpsRange!![supportedFpsRange.size - 1]
					}
					params.setPreviewFpsRange(max_fps!!.get(0), max_fps.get(1))
					params.setRecordingHint(true)
					// request closest supported preview size
					val closestSize = getClosestSupportedSize(
						params.supportedPreviewSizes, width, height)
					params.setPreviewSize(closestSize.width, closestSize.height)
					// request closest picture size for an aspect ratio issue on Nexus7
					val pictureSize = getClosestSupportedSize(
						params.supportedPictureSizes, width, height)
					params.setPictureSize(pictureSize.width, pictureSize.height)
					// rotate camera preview according to the device orientation
					setRotation(camera!!, params)
					camera!!.setParameters(params)
					// get the actual preview size
					val previewSize = camera!!.getParameters().previewSize
					Log.d(TAG, String.format("handleStartPreview(%d, %d),fps%d-%d",
						previewSize.width, previewSize.height, max_fps.get(0), max_fps.get(1)))
					// adjust view size with keeping the aspect ration of camera preview.
					// here is not a UI thread and we should request parent view to execute.
					mView.post(Runnable {
						setVideoSize(previewSize.width, previewSize.height)
						cameraRenderer.onPreviewSizeChanged(previewSize.width, previewSize.height)
					})
					// カメラ映像受け取り用SurfaceTextureをセット
					val st = cameraRenderer.getInputSurfaceTexture()
					st.setDefaultBufferSize(previewSize.width, previewSize.height)
					camera!!.setPreviewTexture(st)
				}
			} catch (e: IOException) {
				Log.e(TAG, "handleStartPreview:", e)
				if (camera != null) {
					camera!!.release()
					camera = null
				}
			} catch (e: RuntimeException) {
				Log.e(TAG, "handleStartPreview:", e)
				if (camera != null) {
					camera!!.release()
					camera = null
				}
			}
			// start camera preview display
			camera?.startPreview()
			synchronized(mSync) {
				mCamera = camera
			}
		}
	}

	/**
	 * カメラプレビュー終了の実体
	 */
	@WorkerThread
	private fun handleStopPreview() {
		if (DEBUG) Log.v(TAG, "CameraThread#handleStopPreview:")
		synchronized(mSync) {
			if (mCamera != null) {
				mCamera!!.stopPreview()
				mCamera!!.release()
				mCamera = null
			}
		}
	}

	/**
	 * 端末の画面の向きに合わせてプレビュー画面を回転させる
	 * @param params
	 */
	@SuppressLint("NewApi")
	private fun setRotation(camera: Camera,
		params: Camera.Parameters) {

		if (DEBUG) Log.v(TAG, "CameraThread#setRotation:")
		val view = mView as View
		val rotation: Int
		rotation = if (BuildCheck.isAPI17()) {
			view.display.rotation
		} else {
			val display = (view.context
				.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
			display.rotation
		}
		var degrees: Int
		degrees = when (rotation) {
			Surface.ROTATION_90 -> 90
			Surface.ROTATION_180 -> 180
			Surface.ROTATION_270 -> 270
			Surface.ROTATION_0 -> 0
			else -> 0
		}
		// get whether the camera is front camera or back camera
		val info = CameraInfo()
		Camera.getCameraInfo(CAMERA_ID, info)
		if (info.facing == CameraInfo.CAMERA_FACING_FRONT) { // front camera
			degrees = (info.orientation + degrees) % 360
			degrees = (360 - degrees) % 360 // reverse
		} else { // back camera
			degrees = (info.orientation - degrees + 360) % 360
		}
		// apply rotation setting
		camera.setDisplayOrientation(degrees)
		mRotation = degrees
		// XXX This method fails to call and camera stops working on some devices.
//		params.setRotation(degrees);
	}

	companion object {
		private const val DEBUG = false // TODO set false on release
		private val TAG = CameraDelegator::class.java.simpleName
		const val SCALE_STRETCH_FIT = 0
		const val SCALE_KEEP_ASPECT_VIEWPORT = 1
		const val SCALE_KEEP_ASPECT = 2
		const val SCALE_CROP_CENTER = 3
		const val DEFAULT_PREVIEW_WIDTH = 1280
		const val DEFAULT_PREVIEW_HEIGHT = 720
		private const val TARGET_FPS_MS = 60 * 1000
		private const val CAMERA_ID = 0

		/**
		 * カメラが対応する解像度一覧から指定した解像度順に一番近いものを選んで返す
		 * @param supportedSizes
		 * @param requestedWidth
		 * @param requestedHeight
		 * @return
		 */
		private fun getClosestSupportedSize(
			supportedSizes: List<Camera.Size>,
			requestedWidth: Int, requestedHeight: Int): Camera.Size {
			return Collections.min(supportedSizes, object : Comparator<Camera.Size> {
				private fun diff(size: Camera.Size): Int {
					return (Math.abs(requestedWidth - size.width)
						+ Math.abs(requestedHeight - size.height))
				}

				override fun compare(lhs: Camera.Size, rhs: Camera.Size): Int {
					return diff(lhs) - diff(rhs)
				}
			})
		}
	}

}
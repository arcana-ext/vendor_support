/*
 * Copyright (C) 2016-2017 The Dirty Unicorns Project
 *               2021 AOSP-Krypton Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.arcana.settings.preference

import android.content.Context
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

import com.arcana.settings.R

open class CustomSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
): Preference(context, attrs), SeekBar.OnSeekBarChangeListener,
        View.OnClickListener, View.OnLongClickListener {

    private var interval = 1
    private var units: String? = null
    private var continuousUpdates = false

    private var minValue = 0
    private var maxValue = 0
    private var defaultValueExists = false
    private var defaultValue: Int = 0
    var seekBarValue = 0

    private var valueTextView: TextView? = null
    private var resetImageView: ImageView? = null
    private var minusImageView: ImageView? = null
    private var plusImageView: ImageView? = null
    private var seekBar: SeekBar? = null

    private var trackingTouch = false
    private var trackingValue: Int

    init {
        context.obtainStyledAttributes(attrs, R.styleable.CustomSeekBarPreference).use {
            units = it.getString(R.styleable.CustomSeekBarPreference_units)
            continuousUpdates = it.getBoolean(R.styleable.CustomSeekBarPreference_continuousUpdates, continuousUpdates)
        }

        minValue = attrs?.getAttributeIntValue(SETTINGS_NS, "min", minValue) ?: minValue
        maxValue = attrs?.getAttributeIntValue(ANDROID_NS, "max", minValue) ?: minValue
        if (maxValue < minValue) {
            maxValue = minValue
        }

        try {
            attrs?.getAttributeValue(SETTINGS_NS, "interval")?.let { interval = Integer.parseInt(it) }
        } catch (ex: NumberFormatException) {
            Log.e(TAG, "Invalid interval value, ${ex.message}")
        }

        val def: String? = attrs?.getAttributeValue(ANDROID_NS, "defaultValue")
        def?.takeIf{ it.isNotEmpty() }?.let {
            try {
                defaultValue = def.toInt()
                if (defaultValue < minValue || defaultValue > maxValue) {
                    defaultValue = minValue
                }
                defaultValueExists = true
                seekBarValue = defaultValue
            } catch (ex: NumberFormatException) {
                Log.e(TAG, "Invalid default value, $ex")
                seekBarValue = minValue
            }
        } ?: run {
            seekBarValue = minValue
        }
        trackingValue = seekBarValue

        setLayoutResource(R.layout.preference_custom_seekbar)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        seekBar = (holder.findViewById(R.id.seekbar) as SeekBar?)?.also {
            it.setMax(maxValue)
            it.setMin(minValue)
            it.setProgress(seekBarValue)
            it.setEnabled(isEnabled())
            it.setOnSeekBarChangeListener(this)
        }

        valueTextView = holder.findViewById(R.id.value) as TextView?
        resetImageView = holder.findViewById(R.id.reset) as ImageView?
        minusImageView = holder.findViewById(R.id.minus) as ImageView?
        plusImageView = holder.findViewById(R.id.plus) as ImageView?

        updateValueViews()

        resetImageView?.setOnClickListener(this)
        minusImageView?.setOnClickListener(this)
        plusImageView?.setOnClickListener(this)

        resetImageView?.setOnLongClickListener(this)
    }

    private fun updateValueViews() {
        valueTextView?.let {
            var text = context.getString(R.string.custom_seekbar_value);
            text += " ${ if (trackingTouch) trackingValue else seekBarValue }"
            if (units != null) {
                text += " $units"
            }
            if (!trackingTouch && defaultValueExists && seekBarValue == defaultValue) {
                text += " (" + context.getString(R.string.custom_seekbar_default_value) + ")"
            }
            it.setText(text)
        }
        resetImageView?.let {
            if (!defaultValueExists || seekBarValue == defaultValue || trackingTouch)
                it.setVisibility(View.INVISIBLE)
            else
                it.setVisibility(View.VISIBLE)
        }
        minusImageView?.setClickable(seekBarValue >= minValue && !trackingTouch)
        plusImageView?.setClickable(seekBarValue <= maxValue && !trackingTouch)
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (trackingTouch && !continuousUpdates) {
            trackingValue = progress
        } else if (seekBarValue != progress) {
            // change rejected, revert to the previous seekBarValue
            if (!callChangeListener(progress)) {
                seekBar.setProgress(seekBarValue)
                return
            }
            // change accepted, store it
            persistInt(progress)

            seekBarValue = progress
        }
        updateValueViews()
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        trackingTouch = true
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        trackingTouch = false
        if (!continuousUpdates) onProgressChanged(seekBar, trackingValue, false)
        notifyChanged()
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.reset -> {
                Toast.makeText(context, context.getString(R.string.custom_seekbar_default_value_to_set,
                    defaultValue), Toast.LENGTH_LONG).show()
            }
            R.id.minus -> {
                val newValue = Math.max(seekBarValue - interval, minValue)
                setValue(newValue)
                if (!shouldPersist()) {
                    callChangeListener(newValue)
                }
            }
            R.id.plus -> {
                val newValue = Math.min(seekBarValue + interval, maxValue)
                setValue(newValue)
                if (!shouldPersist()) {
                    callChangeListener(newValue)
                }
            }
        }
    }

    override fun onLongClick(v: View): Boolean {
        if (v.id == R.id.reset) {
            setValue(defaultValue)
            if (!shouldPersist()) {
                callChangeListener(defaultValue)
            }
            return true
        }
        return false
    }

    override protected fun onSetInitialValue(restoreValue: Boolean, defaultValue: Any?) {
        if (restoreValue) seekBarValue = getPersistedInt(if (defaultValueExists) this.defaultValue else minValue)
    }

    override fun setDefaultValue(defaultValue: Any?) {
        defaultValue?.let {
            if (it is Int) {
                setDefaultValue(it)
            } else if (it is String) {
                setDefaultValue(it.toInt())
            }
        }
    }

    fun setDefaultValue(newValue: Int) {
        if (newValue < minValue || newValue > maxValue) {
            throw IllegalArgumentException("New default value $newValue is out of range")
        }
        if (!defaultValueExists || defaultValue != newValue) {
            defaultValue = newValue
            defaultValueExists = true
            updateValueViews()
        }
    }

    fun setMax(max: Int) {
        if (maxValue != max) {
            maxValue = max
            seekBar?.setMax(maxValue)
        }
    }

    fun setMin(min: Int) {
        if (minValue != min) {
            minValue = min
            seekBar?.setMin(minValue)
        }
    }

    fun setValue(newValue: Int) {
        if (seekBarValue != newValue) {
            if (!shouldPersist()) {
                seekBarValue = newValue
            }
            seekBar?.setProgress(newValue)
            updateValueViews()             
        }
    }

    companion object {
        private const val TAG = "CustomSeekBarPreference"
        private const val SETTINGS_NS = "http://schemas.android.com/apk/res-auto"
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}

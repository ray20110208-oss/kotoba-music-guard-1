# Battery monitoring update

Version: 0.2.5-alpha5-battery1 (13).

The build extracts the hash-verified alpha5 archive, then copies source-overrides into the extracted project. The tracked files here replace the matching archive files.

The visible activity subscribes to ACTION_BATTERY_CHANGED instead of polling its sticky snapshot every 250 ms. It displays battery temperature separately and the elapsed time since receipt of a non-initial battery broadcast. The initial sticky value has unknown age. Receipt time is not sensor measurement time; updates may report unchanged temperatures. No reading is fabricated between reports. Negative temperatures remain valid; missing temperature or absent battery displays unavailable. Registration is scoped to the visible activity and is released on stop. This does not record temperature in the background or alter audio processing.

Validation on a device: open/close and rotate the activity, background and reopen it, verify the initial snapshot is labeled as retained data, verify new system reports restart the receipt-age counter even when temperature is unchanged, and verify counter progression without fresh reports. Sensor cadence is controlled by Android/device firmware, not by this app.

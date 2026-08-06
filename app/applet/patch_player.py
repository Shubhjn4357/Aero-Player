with open("app/src/main/java/com/example/ui/screens/PlayerScreen.kt", "r") as f:
    code = f.read()

# 1. SkipPrevious double tap seeking
target1 = """                                                     onDoubleTap = {
                                                         val seekAmountMs = (prefs.doubleTapSeekSeconds * 1000L)
                                                         val targetSeek = (exoPlayer.currentPosition - seekAmountMs).coerceAtLeast(0L)
                                                         exoPlayer.seekTo(targetSeek)"""

replacement1 = """                                                     onDoubleTap = {
                                                         val seekAmountMs = (prefs.doubleTapSeekSeconds * 1000L)
                                                         val curPos = if (activeEngine == "VLC") vlcPlayer.currentPositionMs else exoPlayer.currentPosition
                                                         val targetSeek = (curPos - seekAmountMs).coerceAtLeast(0L)
                                                         if (activeEngine == "VLC") vlcPlayer.seekTo(targetSeek) else exoPlayer.seekTo(targetSeek)"""

code = code.replace(target1, replacement1)

# 2. SkipNext double tap seeking
target2 = """                                                     onDoubleTap = {
                                                         val seekAmountMs = (prefs.doubleTapSeekSeconds * 1000L)
                                                         val targetSeek = (exoPlayer.currentPosition + seekAmountMs).coerceAtMost(duration)
                                                         exoPlayer.seekTo(targetSeek)"""

replacement2 = """                                                     onDoubleTap = {
                                                         val seekAmountMs = (prefs.doubleTapSeekSeconds * 1000L)
                                                         val curPos = if (activeEngine == "VLC") vlcPlayer.currentPositionMs else exoPlayer.currentPosition
                                                         val targetSeek = (curPos + seekAmountMs).coerceAtMost(duration)
                                                         if (activeEngine == "VLC") vlcPlayer.seekTo(targetSeek) else exoPlayer.seekTo(targetSeek)"""

code = code.replace(target2, replacement2)

# 3. Screen gestures double tap seeking
target3 = """                                         if (exoPlayer.isPlaying) {
                                             exoPlayer.pause()
                                         } else {
                                             exoPlayer.play()
                                         }
                                     } else if (offset.x <= width * 0.30f) {
                                         val targetSeek = (exoPlayer.currentPosition - seekAmountMs).coerceAtLeast(0L)
                                         exoPlayer.seekTo(targetSeek)
                                         currentPosition = targetSeek
                                         gestureFeedbackType = "seek_back"
                                         gestureFeedbackValue = "-${prefs.doubleTapSeekSeconds}s"
                                         leftDoubleTapOffset = offset
                                         leftRippleTrigger++
                                     } else {
                                         val targetSeek = (exoPlayer.currentPosition + seekAmountMs).coerceAtMost(duration)
                                         exoPlayer.seekTo(targetSeek)
                                         currentPosition = targetSeek"""

replacement3 = """                                         if (activeEngine == "VLC") {
                                             if (vlcPlayer.isPlaying) vlcPlayer.pause() else vlcPlayer.play()
                                         } else {
                                             if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                         }
                                     } else if (offset.x <= width * 0.30f) {
                                         val curPos = if (activeEngine == "VLC") vlcPlayer.currentPositionMs else exoPlayer.currentPosition
                                         val targetSeek = (curPos - seekAmountMs).coerceAtLeast(0L)
                                         if (activeEngine == "VLC") vlcPlayer.seekTo(targetSeek) else exoPlayer.seekTo(targetSeek)
                                         currentPosition = targetSeek
                                         gestureFeedbackType = "seek_back"
                                         gestureFeedbackValue = "-${prefs.doubleTapSeekSeconds}s"
                                         leftDoubleTapOffset = offset
                                         leftRippleTrigger++
                                     } else {
                                         val curPos = if (activeEngine == "VLC") vlcPlayer.currentPositionMs else exoPlayer.currentPosition
                                         val targetSeek = (curPos + seekAmountMs).coerceAtMost(duration)
                                         if (activeEngine == "VLC") vlcPlayer.seekTo(targetSeek) else exoPlayer.seekTo(targetSeek)
                                         currentPosition = targetSeek"""

code = code.replace(target3, replacement3)

# 4. AudioColumnContent VLC integration
target_audio = """              @Composable
              fun AudioColumnContent() {
                  val trigger = tracksUpdateTrigger
                  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {"""

replacement_audio = """              @Composable
              fun AudioColumnContent() {
                  val trigger = tracksUpdateTrigger
                  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                      if (activeEngine == "VLC") {
                          val vlcAudioTracks = remember(tracksUpdateTrigger, activeEngine) {
                              vlcPlayer.getAudioTracks()?.filter { it.id != -1 } ?: emptyList()
                          }
                          val selectedVlcAudioId = remember(tracksUpdateTrigger, activeEngine) {
                              vlcPlayer.getSelectedAudioTrack()
                          }
                          Text(text = "Audio Tracks (VLC)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
                          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                          Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                              if (vlcAudioTracks.isEmpty()) {
                                  Text("No audio tracks detected", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                              } else {
                                  vlcAudioTracks.forEach { track ->
                                      val isSelected = track.id == selectedVlcAudioId
                                      Card(
                                          colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent),
                                          shape = RoundedCornerShape(8.dp),
                                          modifier = Modifier.fillMaxWidth().clickable {
                                              vlcPlayer.selectAudioTrack(track.id)
                                              tracksUpdateTrigger++
                                          }
                                      ) {
                                          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)) {
                                              Text(text = track.name ?: "Track ${track.id}", fontSize = 13.sp, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                                              if (isSelected) {
                                                  Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                              }
                                          }
                                      }
                                  }
                              }
                          }
                          return
                      }"""

code = code.replace(target_audio, replacement_audio)

# 5. SubtitlesColumnContent VLC integration
target_sub = """              @Composable
              fun SubtitlesColumnContent() {
                  val trigger = tracksUpdateTrigger
                  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {"""

replacement_sub = """              @Composable
              fun SubtitlesColumnContent() {
                  val trigger = tracksUpdateTrigger
                  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                      if (activeEngine == "VLC") {
                          val vlcSubTracks = remember(tracksUpdateTrigger, activeEngine) {
                              vlcPlayer.getSubtitleTracks() ?: emptyArray()
                          }
                          val selectedVlcSubId = remember(tracksUpdateTrigger, activeEngine) {
                              vlcPlayer.getSelectedSubtitleTrack()
                          }
                          val isDisabled = selectedVlcSubId == -1
                          Text(text = "Subtitles (VLC)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
                          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                          Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                              Card(
                                  colors = CardDefaults.cardColors(containerColor = if (isDisabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent),
                                  shape = RoundedCornerShape(8.dp),
                                  modifier = Modifier.fillMaxWidth().clickable {
                                      vlcPlayer.selectSubtitleTrack(-1)
                                      tracksUpdateTrigger++
                                  }
                              ) {
                                  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)) {
                                      Text("Disable Subtitles", fontSize = 13.sp, color = if (isDisabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isDisabled) FontWeight.Bold else FontWeight.Normal)
                                      if (isDisabled) {
                                          Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                      }
                                  }
                              }
                              vlcSubTracks.filter { it.id != -1 }.forEach { track ->
                                  val isSelected = track.id == selectedVlcSubId && !isDisabled
                                  Card(
                                      colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent),
                                      shape = RoundedCornerShape(8.dp),
                                      modifier = Modifier.fillMaxWidth().clickable {
                                          vlcPlayer.selectSubtitleTrack(track.id)
                                          tracksUpdateTrigger++
                                      }
                                  ) {
                                      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)) {
                                          Text(text = track.name ?: "Subtitle ${track.id}", fontSize = 13.sp, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                                          if (isSelected) {
                                              Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                          }
                                      }
                                  }
                              }
                          }
                          return
                      }"""

code = code.replace(target_sub, replacement_sub)

# 6. File Picker for external subtitle
target_file_sub = """                    showFileBrowserForSubtitle = false
                    try {
                        val subtitleUri = android.net.Uri.fromFile(selectedFile).toString()"""

replacement_file_sub = """                    showFileBrowserForSubtitle = false
                    try {
                        if (activeEngine == "VLC") {
                            vlcPlayer.addExternalSubtitle(android.net.Uri.fromFile(selectedFile))
                            tracksUpdateTrigger++
                            android.widget.Toast.makeText(context, "Added subtitle track to VLC", android.widget.Toast.LENGTH_SHORT).show()
                            return@CustomFileBrowser
                        }
                        val subtitleUri = android.net.Uri.fromFile(selectedFile).toString()"""

code = code.replace(target_file_sub, replacement_file_sub)

# 7. Speed slider setPlaybackSpeed calls
code = code.replace("onValueChangeFinished = { exoPlayer.setPlaybackSpeed(localSpeed) }", "onValueChangeFinished = { exoPlayer.setPlaybackSpeed(localSpeed); vlcPlayer.setSpeed(localSpeed) }")
code = code.replace("exoPlayer.setPlaybackSpeed(preset)", "exoPlayer.setPlaybackSpeed(preset); vlcPlayer.setSpeed(preset)")

with open("app/src/main/java/com/example/ui/screens/PlayerScreen.kt", "w") as f:
    f.write(code)

print("Patch applied successfully!")

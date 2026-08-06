import sys

path = 'app/src/main/java/com/example/ui/screens/PlayerScreen.kt'
with open(path, 'r', encoding='utf-8') as f:
    text = f.read()

old_states = '             var localSubtitlesDisabled by remember(tracksUpdateTrigger) {'
new_states = '''             val hasMultipleVideoFormats = videoTracks.size >= 2
             val availableTabs = remember(hasMultipleVideoFormats) {
                 if (hasMultipleVideoFormats) listOf("Audio", "Subtitles", "Video Quality") else listOf("Audio", "Subtitles")
             }
             var selectedSheetTab by remember { androidx.compose.runtime.mutableIntStateOf(0) }

             var localSubtitlesDisabled by remember(tracksUpdateTrigger) {'''

if old_states in text:
    text = text.replace(old_states, new_states, 1)
    print('Added tab state variables!')

old_wrapper = '''              // Content wrapper
              Column(
                  modifier = Modifier
                      .fillMaxWidth()
                      .navigationBarsPadding()
                      .verticalScroll(rememberScrollState()) // Parent is scrollable, preventing option cutoffs
                      .padding(horizontal = 24.dp, vertical = 12.dp)
              ) {
                  Spacer(modifier = Modifier.height(16.dp))
                  Text(
                      text = "Audio & Subtitles Settings",
                      fontWeight = FontWeight.Bold,
                      fontSize = 18.sp,
                      color = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.align(Alignment.CenterHorizontally)
                  )
                  Spacer(modifier = Modifier.height(20.dp))

                  if (isLandscape) {
                      Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.spacedBy(16.dp)
                      ) {
                          Box(modifier = Modifier.weight(1f)) { AudioColumnContent() }
                          Box(modifier = Modifier.weight(1f)) { SubtitlesColumnContent() }
                          Box(modifier = Modifier.weight(1f)) { VideoColumnContent() }
                      }
                  } else {
                      Column(
                          modifier = Modifier.fillMaxWidth(),
                          verticalArrangement = Arrangement.spacedBy(16.dp)
                      ) {
                          AudioColumnContent()
                          SubtitlesColumnContent()
                          VideoColumnContent()
                      }
                  }
                  Spacer(modifier = Modifier.height(24.dp))
              }'''

new_wrapper = '''              // Content wrapper
              Column(
                  modifier = Modifier
                      .fillMaxWidth()
                      .navigationBarsPadding()
                      .verticalScroll(rememberScrollState()) // Parent is scrollable, preventing option cutoffs
                      .padding(horizontal = 24.dp, vertical = 12.dp)
              ) {
                  Spacer(modifier = Modifier.height(16.dp))
                  Text(
                      text = "Audio & Subtitles Settings",
                      fontWeight = FontWeight.Bold,
                      fontSize = 18.sp,
                      color = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.align(Alignment.CenterHorizontally)
                  )
                  Spacer(modifier = Modifier.height(16.dp))

                  if (isLandscape) {
                      Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.spacedBy(16.dp)
                      ) {
                          Box(modifier = Modifier.weight(1f)) { AudioColumnContent() }
                          Box(modifier = Modifier.weight(1f)) { SubtitlesColumnContent() }
                          if (hasMultipleVideoFormats) {
                              Box(modifier = Modifier.weight(1f)) { VideoColumnContent() }
                          }
                      }
                  } else {
                      TabRow(
                          selectedTabIndex = selectedSheetTab.coerceIn(0, availableTabs.size - 1),
                          containerColor = Color.Transparent,
                          contentColor = MaterialTheme.colorScheme.primary,
                          modifier = Modifier.fillMaxWidth()
                      ) {
                          availableTabs.forEachIndexed { index, title ->
                              Tab(
                                  selected = (selectedSheetTab.coerceIn(0, availableTabs.size - 1) == index),
                                  onClick = { selectedSheetTab = index },
                                  text = {
                                      Text(
                                          text = title,
                                          fontWeight = if (selectedSheetTab.coerceIn(0, availableTabs.size - 1) == index) FontWeight.Bold else FontWeight.Normal,
                                          fontSize = 14.sp
                                      )
                                  }
                              )
                          }
                      }
                      Spacer(modifier = Modifier.height(16.dp))
                      when (selectedSheetTab.coerceIn(0, availableTabs.size - 1)) {
                          0 -> AudioColumnContent()
                          1 -> SubtitlesColumnContent()
                          2 -> if (hasMultipleVideoFormats) VideoColumnContent() else AudioColumnContent()
                      }
                  }
                  Spacer(modifier = Modifier.height(24.dp))
              }'''

if old_wrapper in text:
    text = text.replace(old_wrapper, new_wrapper, 1)
    print('Replaced wrapper!')
else:
    print('Wrapper not found!')

with open(path, 'w', encoding='utf-8') as f:
    f.write(text)

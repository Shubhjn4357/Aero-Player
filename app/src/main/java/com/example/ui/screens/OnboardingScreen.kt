package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler

data class OnboardingPage(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val accentColor: Color
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit
) {
    val pages = listOf(
        OnboardingPage(
            title = "Aero Player",
            description = "Experience premium, high-fidelity audio & video playback styled with elegant, warm aesthetics and zero clutter.",
            icon = Icons.Default.PlayCircleFilled,
            accentColor = MaterialTheme.colorScheme.secondary
        ),
        OnboardingPage(
            title = "Physical Simplicity",
            description = "Enjoy tactile visual structures, fluid swipe gestures, and intuitive bottom drawers that adapt beautifully to your device.",
            icon = Icons.Default.MusicNote,
            accentColor = MaterialTheme.colorScheme.primary
        ),
        OnboardingPage(
            title = "Automated Scanning",
            description = "Instantly scan local files upon launch and easily stream network links. Simple, clean, and private.",
            icon = Icons.Default.Speed,
            accentColor = MaterialTheme.colorScheme.secondary
        )
    )

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch {
            pagerState.animateScrollToPage(pagerState.currentPage - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Skip button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onFinished) {
                    Text(
                        text = "Skip",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { position ->
                val page = pages[position]
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Accent circle with icon
                    if (position == 0) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(24.dp)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                val context = androidx.compose.ui.platform.LocalContext.current
                                val bannerPainter: androidx.compose.ui.graphics.painter.Painter? = remember(context) {
                                    try {
                                        val drawable = androidx.core.content.ContextCompat.getDrawable(context, com.example.R.drawable.img_aero_hero_banner)
                                        if (drawable != null) {
                                            val bitmap = android.graphics.Bitmap.createBitmap(
                                                drawable.intrinsicWidth.coerceAtLeast(1),
                                                drawable.intrinsicHeight.coerceAtLeast(1),
                                                android.graphics.Bitmap.Config.ARGB_8888
                                            )
                                            val canvas = android.graphics.Canvas(bitmap)
                                            drawable.setBounds(0, 0, canvas.width, canvas.height)
                                            drawable.draw(canvas)
                                            androidx.compose.ui.graphics.painter.BitmapPainter(
                                                bitmap.asImageBitmap()
                                            )
                                        } else {
                                            null
                                        }
                                    } catch (t: Throwable) {
                                        android.util.Log.e("OnboardingScreen", "Failed to load hero banner", t)
                                        null
                                    }
                                }

                                if (bannerPainter != null) {
                                    Image(
                                        painter = bannerPainter,
                                        contentDescription = "Aero Player Banner",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    // Beautiful fallback linear gradient with audio waves visual
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                androidx.compose.ui.graphics.Brush.linearGradient(
                                                    colors = listOf(
                                                        MaterialTheme.colorScheme.primary,
                                                        MaterialTheme.colorScheme.tertiary,
                                                        MaterialTheme.colorScheme.secondary
                                                    )
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val waveHeights = listOf(24.dp, 48.dp, 36.dp, 60.dp, 40.dp, 56.dp, 30.dp, 48.dp, 20.dp)
                                            waveHeights.forEach { height ->
                                                Box(
                                                    modifier = Modifier
                                                        .width(4.dp)
                                                        .height(height)
                                                        .background(Color.White.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                                                )
                                            }
                                        }
                                    }
                                }
                                // Overlaid semi-transparent layer for better readability
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.45f))
                                )
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.15f))
                                        .align(Alignment.Center),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = page.icon,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .clip(CircleShape)
                                .background(page.accentColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = page.icon,
                                contentDescription = null,
                                tint = page.accentColor,
                                modifier = Modifier.size(64.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(40.dp))

                    Text(
                        text = page.title,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = page.description,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                }
            }

            // Bottom controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Page Indicator dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    repeat(pages.size) { index ->
                        val active = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .height(8.dp)
                                .width(if (active) 24.dp else 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.secondary
                                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f)
                                )
                        )
                    }
                }

                // Next / Finish button
                Button(
                    onClick = {
                        if (pagerState.currentPage == pages.size - 1) {
                            onFinished()
                        } else {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (pagerState.currentPage == pages.size - 1) "Get Started" else "Next",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Default.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

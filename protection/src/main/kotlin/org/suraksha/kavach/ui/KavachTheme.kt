package org.suraksha.kavach.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Forest: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF34D399) else Color(0xFF067647)
val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
val Cream: Color @Composable get() = MaterialTheme.colorScheme.background
val Danger: Color @Composable get() = MaterialTheme.colorScheme.error
val Caution: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFBBF24) else Color(0xFF805000)

@Composable
fun KavachTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme(
            primary = Color(0xFF60A5FA), onPrimary = Color(0xFF101828), primaryContainer = Color(0xFF172A46),
            onPrimaryContainer = Color(0xFFDBEAFE), secondary = Color(0xFF34D399), onSecondary = Color(0xFF101828),
            secondaryContainer = Color(0xFF102F29), onSecondaryContainer = Color(0xFFD1FAE5),
            background = Color(0xFF0B1220), onBackground = Color(0xFFF8FAFC),
            surface = Color(0xFF111827), onSurface = Color(0xFFF8FAFC),
            surfaceVariant = Color(0xFF1F2937), onSurfaceVariant = Color(0xFFCBD5E1),
            outline = Color(0xFF64748B), error = Color(0xFFFF5A52)
        ) else lightColorScheme(
            primary = Color(0xFF101828), onPrimary = Color.White, primaryContainer = Color(0xFFEFF6FF),
            onPrimaryContainer = Color(0xFF101828), secondary = Color(0xFF2563EB), onSecondary = Color.White,
            secondaryContainer = Color(0xFFEFF6FF), onSecondaryContainer = Color(0xFF101828),
            background = Color(0xFFF8FAFC), onBackground = Color(0xFF101828),
            surface = Color.White, onSurface = Color(0xFF101828), surfaceVariant = Color(0xFFF2F4F7),
            onSurfaceVariant = Color(0xFF475467), outline = Color(0xFF667085), error = Color(0xFFB42318)
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
            headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
            titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 29.sp),
            bodyMedium = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
            labelLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
        ), content = content
    )
}

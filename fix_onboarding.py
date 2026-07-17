import re

file_path = r'D:\Guardian ANTI THIEF\NEW_OS_TESTING_ZONE\guardian\app\src\main\java\com\example\MainActivity.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Find the second OnboardingPage2 occurrence
pattern = '\n}\n\n@Composable\nprivate fun OnboardingPage2() {'
first_idx = content.find(pattern)
second_idx = content.find(pattern, first_idx + 1)

if second_idx > 0:
    content = content[:second_idx]

# Find LoginScreen start
login_idx = content.rfind('\n}\n\n@Composable\nfun LoginScreen(')
if login_idx > 0:
    content = content[:login_idx + 1]

# Append OnboardingPage3 and LoginScreen
page3_lines = [
    '\n}',
    '',
    '@Composable',
    'private fun OnboardingPage3() {',
    '  Box(',
    '    modifier = Modifier',
    '      .size(240.dp)',
    '      .weight(1f),',
    '    contentAlignment = Alignment.Center',
    '  ) {',
    '    val infiniteTransition = rememberInfiniteTransition(label = "AdminPulse")',
    '    val pulse by infiniteTransition.animateFloat(',
    '      initialValue = 0.8f,',
    '      targetValue = 1f,',
    '      animationSpec = infiniteRepeatable(',
    '        animation = tween(1200, easing = EaseInOutSine),',
    '        repeatMode = RepeatMode.Reverse',
    '      ),',
    '      label = "pulse"',
    '    )',
    '',
    '    Box(',
    '      modifier = Modifier',
    '        .size(160.dp)',
    '        .scale(pulse),',
    '      contentAlignment = Alignment.Center',
    '    ) {',
    '      Canvas(modifier = Modifier.fillMaxSize()) {',
    '        drawCircle(',
    '          color = Color(0xFFB6C4FF).copy(alpha = 0.1f),',
    '          radius = size.width / 2',
    '        )',
    '        drawCircle(',
    '          color = Color(0xFFB6C4FF).copy(alpha = 0.2f),',
    '          radius = size.width / 2 * 0.7f,',
    '          style = Stroke(width = 1.dp.toPx())',
    '        )',
    '      }',
    '',
    '      Icon(',
    '        imageVector = Icons.Default.AdminPanelSettings,',
    '        contentDescription = "Admin panel icon",',
    '        tint = Color(0xFFB6C4FF),',
    '        modifier = Modifier.size(64.dp)',
    '      )',
    '    }',
    '  }',
    '}',
    '',
    '@Composable',
    'fun LoginScreen(',
]

content += '\n' + '\n'.join(page3_lines)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print('Done - file fixed')

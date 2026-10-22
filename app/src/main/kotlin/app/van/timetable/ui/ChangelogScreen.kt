// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ChangelogScreen() {
    val context = LocalContext.current
    val entries = remember(context) {
        context.assets.open("changelog.txt").bufferedReader().use { it.readText() }
            .split("\n## ").filter { it.isNotBlank() }.map { entry ->
                entry.removePrefix("## ").substringBefore('\n') to entry.substringAfter('\n').trim()
            }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("changelog-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(entries, key = { it.first }) { (title, body) ->
            var expanded by rememberSaveable(title) { mutableStateOf(title.startsWith("0.2.6")) }
            Panel {
                Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp)) {
                    Text(title, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Text(if (expanded) "收起" else "展开", color = LocalVan.current.blue)
                }
                if (expanded) Text(body)
            }
        }
    }
}

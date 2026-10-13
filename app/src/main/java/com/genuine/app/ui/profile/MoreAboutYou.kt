package com.genuine.app.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.genuine.app.ui.common.Opt
import com.genuine.app.ui.common.ProfileOptions
import com.genuine.app.ui.common.cmToFeet

@Composable
fun MoreAboutYou(vm: ProfileViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column {
            Text("More about you", style = MaterialTheme.typography.titleMedium)
            Text("All optional. Tap a selected option again to clear it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ----- Looking for -----
        ChoiceGroup("Looking for", ProfileOptions.goals, vm.goal) { vm.goal = it }
        ChoiceGroup("Kids", ProfileOptions.kids, vm.kids) { vm.kids = it }

        // ----- Basics -----
        OutlinedTextField(
            value = vm.heightText,
            onValueChange = { vm.heightText = it.filter(Char::isDigit).take(3) },
            label = { Text("Height (cm)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = !vm.heightValid,
            supportingText = {
                when {
                    !vm.heightValid -> Text("Enter a height between 120 and 230 cm")
                    vm.heightCm != null -> Text(cmToFeet(vm.heightCm!!))
                    else -> Text("e.g. 170")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        ChoiceGroup("Education", ProfileOptions.education, vm.education) { vm.education = it }
        OutlinedTextField(
            vm.jobTitle, { vm.jobTitle = it.take(40) },
            label = { Text("Job title") },
            supportingText = { Text("No company name, for your privacy") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            vm.hometown, { vm.hometown = it.take(40) },
            label = { Text("Hometown") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        LanguagesGroup(vm)

        // ----- Lifestyle -----
        ChoiceGroup("Drinking", ProfileOptions.drinking, vm.drinking) { vm.drinking = it }
        ChoiceGroup("Smoking", ProfileOptions.smoking, vm.smoking) { vm.smoking = it }
        ChoiceGroup("Exercise", ProfileOptions.exercise, vm.exercise) { vm.exercise = it }
        ChoiceGroup("Diet", ProfileOptions.diet, vm.diet) { vm.diet = it }

        // ----- Prompts -----
        PromptsEditor(vm)
    }
}

/** Single choice; tapping the selected chip clears it (every field is optional). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceGroup(title: String, options: List<Opt>, selected: String?, onSelect: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                FilterChip(
                    selected = selected == o.key,
                    onClick = { onSelect(if (selected == o.key) null else o.key) },
                    label = { Text(o.edit) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguagesGroup(vm: ProfileViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Languages (${vm.languages.size}/${ProfileOptions.MAX_LANGUAGES})",
            style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileOptions.languages.forEach { lang ->
                val selected = lang in vm.languages
                FilterChip(
                    selected = selected,
                    onClick = { vm.toggleLanguage(lang) },
                    label = { Text(lang) },
                    enabled = selected || vm.languages.size < ProfileOptions.MAX_LANGUAGES,
                )
            }
        }
    }
}

@Composable
private fun PromptsEditor(vm: ProfileViewModel) {
    var menu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Prompts (${vm.prompts.size}/${ProfileOptions.MAX_PROMPTS})",
            style = MaterialTheme.typography.titleSmall)
        Text("Answer up to 3. They're shown between your photos.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        vm.prompts.forEach { pa ->
            val question = ProfileOptions.prompts.firstOrNull { it.key == pa.q }?.edit ?: pa.q
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(question, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                        IconButton({ vm.removePrompt(pa.q) }) { Icon(Icons.Default.Close, "Remove prompt") }
                    }
                    OutlinedTextField(
                        pa.a, { vm.updatePrompt(pa.q, it) },
                        placeholder = { Text("Your answer") },
                        minLines = 2,
                        supportingText = { Text("${pa.a.length}/${ProfileOptions.PROMPT_MAX_CHARS}") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (vm.prompts.size < ProfileOptions.MAX_PROMPTS) {
            Box {
                OutlinedButton({ menu = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add a prompt")
                }
                DropdownMenu(menu, { menu = false }) {
                    ProfileOptions.prompts.filter { o -> vm.prompts.none { it.q == o.key } }.forEach { o ->
                        DropdownMenuItem(text = { Text(o.edit) }, onClick = { menu = false; vm.addPrompt(o.key) })
                    }
                }
            }
        }
    }
}
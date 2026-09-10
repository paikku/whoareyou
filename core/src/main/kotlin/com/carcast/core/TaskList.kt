package com.carcast.core

/**
 * Where Android currently keeps each app's task, parsed from `am stack list` (ActivityManagerShellCommand
 * `runStackList`, Android 12+). The shell server asks this before starting an app on the virtual display:
 *
 * ```
 * RootTask id=12 bounds=[0,0][1080,2340] displayId=0 userId=0
 *   configuration={...}
 *     taskId=1234: com.google.android.youtube/com.google.android.youtube.app.honeycomb.Shell$HomeActivity
 *       bounds=[0,0][1080,2340]
 * ```
 *
 * Why it matters: `am start --display N` with an app that already has a task **reuses that task and moves it**
 * to display N (ActivityStarter.setTargetRootTaskIfNeeded → Task.reparent), whatever display it is on. So the
 * app the user is using on the phone jumps to the car, and the launcher on the phone pulls it back the same
 * way — that ping-pong is the "conflict" seen in the car. Knowing the display up front lets the server say
 * what will happen and restart the app instead of moving it (see DisplayVideoSource.startApp).
 *
 * Pure Kotlin so the parser is unit-tested; older ROMs printed `Stack id=` instead of `RootTask id=`, and the
 * parser only relies on the `displayId=` and `taskId=` tokens, not on the line prefix.
 */
object TaskList {
    data class Task(val displayId: Int, val taskId: Int, val name: String) {
        /** The package of the task's base activity (`pkg/.Activity` → `pkg`, or the bare name when there is no `/`). */
        val packageName: String get() = name.substringBefore('/')
    }

    private val displayRe = Regex("""\bdisplayId=(-?\d+)""")
    private val taskRe = Regex("""^\s*taskId=(\d+):\s*(\S+)""")

    fun parse(text: String): List<Task> {
        val out = ArrayList<Task>()
        var display = Int.MIN_VALUE
        for (line in text.lineSequence()) {
            // Headers look like "RootTask id=… bounds=… displayId=… userId=…"; a "taskId=" line has no displayId.
            displayRe.find(line)?.let { display = it.groupValues[1].toInt() }
            val t = taskRe.find(line) ?: continue
            if (display == Int.MIN_VALUE) continue
            out += Task(display, t.groupValues[1].toInt(), t.groupValues[2])
        }
        return out
    }

    /** Every task whose base activity belongs to [packageName], in the order `am` listed them (front-most first per display). */
    fun tasksOf(text: String, packageName: String): List<Task> = parse(text).filter { it.packageName == packageName }

    /** The display the app's front-most task is on, or null when it has no task at all. */
    fun displayOf(text: String, packageName: String): Int? = tasksOf(text, packageName).firstOrNull()?.displayId
}

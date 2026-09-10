package com.carcast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskListTest {
    // Shape of `am stack list` on Android 12+ (ActivityManagerShellCommand.runStackList), two displays.
    private val sample = """
        RootTask id=1 bounds=[0,0][1080,2340] displayId=0 userId=0
          configuration={1.0 ?mcc?mnc [ko_KR] ldltr sw411dp w411dp h866dp 420dpi nrml long port finger -keyb/v/h -nav/h winConfig={ mBounds=Rect(0, 0 - 1080, 2340) mAppBounds=Rect(0, 0 - 1080, 2340) mWindowingMode=fullscreen mDisplayWindowingMode=fullscreen mActivityType=undefined mAlwaysOnTop=undefined mRotation=ROTATION_0} s.1 fontWeightAdjustment=0}
            taskId=57: com.sec.android.app.launcher/com.sec.android.app.launcher.activities.LauncherActivity
              bounds=[0,0][1080,2340]
        RootTask id=42 bounds=[0,0][1080,2340] displayId=0 userId=0
          configuration={...}
            taskId=1234: com.google.android.youtube/com.google.android.youtube.app.honeycomb.Shell${'$'}HomeActivity
              bounds=[0,0][1080,2340]
        RootTask id=43 bounds=[0,0][1280,720] displayId=7 userId=0
          configuration={...}
            taskId=1240: com.android.chrome/com.google.android.apps.chrome.Main
              bounds=[0,0][1280,720]
            taskId=1239: com.spotify.music/com.spotify.music.MainActivity
    """.trimIndent()

    @Test
    fun tasksCarryTheDisplayOfTheirRootTask() {
        val tasks = TaskList.parse(sample)
        assertEquals(4, tasks.size)
        assertEquals(TaskList.Task(0, 1234, "com.google.android.youtube/com.google.android.youtube.app.honeycomb.Shell\$HomeActivity"), tasks[1])
        assertEquals(listOf(0, 0, 7, 7), tasks.map { it.displayId })
        assertEquals("com.spotify.music", tasks[3].packageName)
    }

    @Test
    fun displayOfFindsThePackageOnEitherDisplay() {
        assertEquals(0, TaskList.displayOf(sample, "com.google.android.youtube"))
        assertEquals(7, TaskList.displayOf(sample, "com.android.chrome"))
        assertEquals(7, TaskList.displayOf(sample, "com.spotify.music"))
        assertNull(TaskList.displayOf(sample, "com.example.notrunning"))
    }

    @Test
    fun packagePrefixDoesNotMatch() {
        // "com.android.chrome" must not match "com.android.chromebeta" and vice versa.
        assertNull(TaskList.displayOf(sample, "com.android"))
        assertNull(TaskList.displayOf(sample, "com.google.android.youtube.music"))
    }

    @Test
    fun oldStackWordingAndBareNamesStillParse() {
        val old = """
            Stack id=3 bounds=[0,0][720,1280] displayId=2 userId=0
              taskId=9: com.example.app
        """.trimIndent()
        assertEquals(listOf(TaskList.Task(2, 9, "com.example.app")), TaskList.parse(old))
        assertEquals(2, TaskList.displayOf(old, "com.example.app"))
    }

    @Test
    fun garbageAndEmptyInputGiveNothing() {
        assertEquals(emptyList<TaskList.Task>(), TaskList.parse(""))
        assertEquals(emptyList<TaskList.Task>(), TaskList.parse("taskId=5: com.x/.A\nno display header at all"))
        assertEquals(emptyList<TaskList.Task>(), TaskList.parse("Error: unknown command 'stack'"))
    }
}

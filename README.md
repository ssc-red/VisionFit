# Vision Fit 🏋️‍♂️📱

Vision Fit is an AI-powered Android application designed to help you balance physical health with digital well-being. By utilizing on-device machine learning, the app requires you to complete physical exercises to earn "credits," which are then consumed as you use distracting apps.

## 🚀 Features

-   **AI Pose Detection**: Real-time exercise tracking using Google ML Kit.
    -   **Exercises**: Pushups, Squats, Pull Ups, Crunches, and Plank.
-   **App Blocking & Credit System**:
    -   Select apps to "lock" (e.g., Instagram, TikTok, YouTube).
    -   Locked apps require "credits" to stay in the foreground.
    -   Earn credits by exercising (e.g., 1 rep = 30 seconds of app time).
-   **Intelligent Alarms**:
    -   Workout alarms that can only be dismissed by completing a target number of reps.
    -   Ensures you start your day with movement.
-   **Privacy First**: All pose detection and app tracking happens locally on your device. No camera footage or usage data ever leaves the phone.
-   **Daily Grant**: Configurable free credits provided every day to ensure basic connectivity.

## 🛠 Tech Stack

-   **Language**: [Kotlin](https://kotlinlang.org/)
-   **UI Framework**: [Jetpack Compose](https://developer.android.com/jetpack/compose)
-   **Pose Detection**: [Google ML Kit (Accurate Pose Detector)](https://developers.google.com/ml-kit/vision/pose-detection)
-   **Camera Pipeline**: [CameraX](https://developer.android.com/training/camerax)
-   **Local Storage**: [Jetpack DataStore (Preferences)](https://developer.android.com/topic/libraries/architecture/datastore)
-   **App Monitoring**: [Accessibility Service](https://developer.android.com/guide/topics/ui/accessibility/service) & [Usage Stats API](https://developer.android.com/reference/android/app/usage/UsageStatsManager)

## 📦 Installation & Download

### 📥 Download APK
Pre-built APKs are available in the **Releases** section of this repository. You can download the latest version and install it directly on your Android device.

### 🏗 Build from Source
1.  Clone the repository:
    ```bash
    git clone https://github.com/yourusername/VisionFit.git
    ```
2.  Open the project in **Android Studio (Ladybug or newer)**.
3.  Ensure you have an Android device running **Android 10 (API 29)** or higher.
4.  Build and Run the `app` module.

## 📖 How to Use

1.  **Grant Permissions**: The app requires Camera (for workouts), Accessibility (for app blocking), and Usage Stats (for credit consumption) permissions.
2.  **Set Up Rules**: Navigate to the "Apps" tab and select which applications you want to restrict.
3.  **Exercise**: Go to the "Home" tab, select an exercise, and start moving! Your reps will automatically be counted, and credits will be added to your balance.
4.  **Stay Focused**: When you open a blocked app, a timer will show your remaining credits. If you run out, the app will be covered by a workout prompt.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

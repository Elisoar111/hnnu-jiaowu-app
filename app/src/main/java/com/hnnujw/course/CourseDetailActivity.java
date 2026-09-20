package com.hnnujw.course;

import androidx.appcompat.app.AppCompatActivity;

public class CourseDetailActivity extends AppCompatActivity {
    @Override
    protected void attachBaseContext(android.content.Context context) {
        super.attachBaseContext(com.hnnujw.course.manager.AppThemeCoordinator.INSTANCE.wrapContext(context));
    }
}

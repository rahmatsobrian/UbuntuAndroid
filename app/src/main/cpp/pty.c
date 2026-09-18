#include <jni.h>
#include <pty.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>

static char **build_argv(JNIEnv *env, jobjectArray argvObj, int *out_argc) {
    int argc = (*env)->GetArrayLength(env, argvObj);
    char **argv = (char **) calloc((size_t) argc + 1, sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, argvObj, i);
        const char *cs = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i] = strdup(cs);
        (*env)->ReleaseStringUTFChars(env, s, cs);
        (*env)->DeleteLocalRef(env, s);
    }
    argv[argc] = NULL;
    *out_argc = argc;
    return argv;
}

JNIEXPORT jintArray JNICALL
Java_dev_ubuntu4a_core_proot_PtyNative_fork(JNIEnv *env, jclass clazz,
                                            jint cols, jint rows, jobjectArray argvObj) {
    int argc = 0;
    char **argv = build_argv(env, argvObj, &argc);
    if (argc == 0) { free(argv); return NULL; }

    int master = -1;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);

    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid == 0) {
        setenv("TERM", "xterm-256color", 0);
        execvp(argv[0], argv);
        perror("u4a-pty execvp");
        _exit(127);
    }

    free(argv);

    jintArray result = (*env)->NewIntArray(env, 2);
    jint out[2];
    out[0] = (jint) pid;
    out[1] = master;
    if (result != NULL) (*env)->SetIntArrayRegion(env, result, 0, 2, out);
    if (pid < 0 && master >= 0) close(master);
    return result;
}

JNIEXPORT void JNICALL
Java_dev_ubuntu4a_core_proot_PtyNative_resize(JNIEnv *env, jclass clazz, jint fd, jint cols, jint rows) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_dev_ubuntu4a_core_proot_PtyNative_reap(JNIEnv *env, jclass clazz, jint pid) {
    int status = 0;
    waitpid((pid_t) pid, &status, 0);
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

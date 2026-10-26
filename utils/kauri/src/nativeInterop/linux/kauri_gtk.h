#ifndef KAURI_GTK_H
#define KAURI_GTK_H

#ifdef __cplusplus
extern "C" {
#endif

typedef int gboolean;
typedef unsigned int guint;
typedef unsigned long gulong;
typedef void *gpointer;
typedef struct _GtkWidget GtkWidget;
typedef struct _GtkWindow GtkWindow;
typedef struct _GtkContainer GtkContainer;
typedef union _GdkEvent GdkEvent;
typedef struct _WebKitWebContext WebKitWebContext;
typedef struct _WebKitWebView WebKitWebView;
typedef struct _WebKitPolicyDecision WebKitPolicyDecision;
typedef struct _WebKitNavigationPolicyDecision WebKitNavigationPolicyDecision;
typedef struct _WebKitNavigationAction WebKitNavigationAction;
typedef struct _WebKitURIRequest WebKitURIRequest;
typedef struct _WebKitSettings WebKitSettings;
typedef struct _WebKitEditorState WebKitEditorState;
typedef struct { guint domain; int code; char *message; } GError;

/* Kodex resolves function addresses lazily; no GUI library is required before main. */

#ifdef __cplusplus
}
#endif

#endif

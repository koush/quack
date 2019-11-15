#if !defined(DUK_TRANS_SOCKET_H_INCLUDED)
#define DUK_TRANS_SOCKET_H_INCLUDED

#include "duktape.h"

#if defined(__cplusplus)
extern "C" {
#endif

struct client_sock_t {
    int client_sock;
};

void duk_trans_socket_init(void);
void duk_trans_socket_finish(struct client_sock_t *client_sock);
void duk_trans_socket_waitconn(struct client_sock_t *client_sock);
duk_size_t duk_trans_socket_read_cb(void *udata, char *buffer, duk_size_t length);
duk_size_t duk_trans_socket_write_cb(void *udata, const char *buffer, duk_size_t length);
duk_size_t duk_trans_socket_peek_cb(void *udata);
void duk_trans_socket_read_flush_cb(void *udata);
void duk_trans_socket_write_flush_cb(void *udata);
void duk_trans_socket_detached_cb(duk_context *ctx, void *udata);

#if defined(__cplusplus)
/* end 'extern "C"' wrapper */
}
#endif


#endif  /* DUK_TRANS_SOCKET_H_INCLUDED */

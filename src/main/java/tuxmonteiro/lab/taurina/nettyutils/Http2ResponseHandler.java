package tuxmonteiro.lab.taurina.nettyutils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.util.CharsetUtil;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import tuxmonteiro.lab.taurina.services.LoaderService;
import tuxmonteiro.lab.taurina.entity.ReportService;

public class Http2ResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {

    private static final Log LOGGER = LogFactory.getLog(LoaderService.class);
    private final ReportService reportService;

    public Http2ResponseHandler(ReportService reportService) {
        this.reportService = reportService;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        reportService.connIncr();
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        reportService.connDecr();
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) throws Exception {
        Integer streamId = msg.headers().getInt(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text());
        if (streamId == null) {
            LOGGER.error("HttpResponseHandler unexpected message received: " + msg);
            return;
        }

        final ByteBuf content = msg.content();
        if (content.isReadable()) {
            int contentLength = content.readableBytes();
            reportService.bodySizeAccumalator(contentLength);
            reportService.responseIncr();
            if (LOGGER.isDebugEnabled()) {
                byte[] arr = new byte[contentLength];
                content.readBytes(arr);
                LOGGER.debug("stream_id=" + streamId + " : " + new String(arr, 0, contentLength, CharsetUtil.UTF_8));
            }
        }

    }
}
package tuxmonteiro.lab.taurina.nettyutils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import tuxmonteiro.lab.taurina.entity.ReportService;

class Http1ClientHandler extends SimpleChannelInboundHandler<HttpObject> {

    private final ReportService reportService;

    public Http1ClientHandler(ReportService reportService) {
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
    public void channelRead0(ChannelHandlerContext channelHandlerContext, HttpObject msg) throws Exception {

        if (msg instanceof HttpResponse) {
            reportService.responseIncr();
            HttpResponse response = (HttpResponse) msg;
            reportService.bodySizeAccumalator(response.toString().length());
        }

        if (msg instanceof HttpContent) {
            HttpContent content = (HttpContent) msg;
            ByteBuf byteBuf = content.content();
            if (byteBuf.isReadable()) {
                reportService.bodySizeAccumalator(byteBuf.readableBytes());
            }
        }
    }

}

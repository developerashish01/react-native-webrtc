/**
 * Switches the DeepAR effect for a given video track.
 * @param trackId The id of the video track.
 * @param effectPath The path to the new DeepAR effect.
 */
export function switchDeepAREffect(trackId: string, effectPath: string) {
    Logger.info('[switchDeepAREffect] called', { trackId, effectPath });
    if (WebRTCModule && typeof WebRTCModule.switchDeepAREffect === 'function') {
        try {
            Logger.info('[switchDeepAREffect] invoking WebRTCModule.switchDeepAREffect', { trackId, effectPath });
            WebRTCModule.switchDeepAREffect(trackId, effectPath);
            Logger.info('[switchDeepAREffect] WebRTCModule.switchDeepAREffect call finished');
        } catch (err) {
            Logger.error('[switchDeepAREffect] Exception during WebRTCModule.switchDeepAREffect', err);
            throw err;
        }
    } else {
        Logger.error('[switchDeepAREffect] WebRTCModule.switchDeepAREffect is not available', { WebRTCModule });
        throw new Error('WebRTCModule.switchDeepAREffect is not available');
    }
}
import { NativeModules, Platform } from 'react-native';
const { WebRTCModule } = NativeModules;

if (WebRTCModule === null) {
    throw new Error(`WebRTC native module not found.\n${Platform.OS === 'ios' ?
        'Try executing the "pod install" command inside your projects ios folder.' :
        'Try executing the "npm install" command inside your projects folder.'
    }`);
}

import { setupNativeEvents } from './EventEmitter';
import Logger from './Logger';
import mediaDevices from './MediaDevices';
import MediaStream from './MediaStream';
import MediaStreamTrack, { type MediaTrackSettings } from './MediaStreamTrack';
import MediaStreamTrackEvent from './MediaStreamTrackEvent';
import permissions from './Permissions';
import RTCAudioSession from './RTCAudioSession';
import RTCErrorEvent from './RTCErrorEvent';
import RTCIceCandidate from './RTCIceCandidate';
import RTCPIPView, { startIOSPIP, stopIOSPIP } from './RTCPIPView';
import RTCPeerConnection from './RTCPeerConnection';
import RTCRtpEncodingParameters, { type RTCRtpEncodingParametersInit } from './RTCRtpEncodingParameters';
import RTCRtpReceiver from './RTCRtpReceiver';
import RTCRtpSendParameters, { type RTCRtpSendParametersInit } from './RTCRtpSendParameters';
import RTCRtpSender from './RTCRtpSender';
import RTCRtpTransceiver from './RTCRtpTransceiver';
import RTCSessionDescription from './RTCSessionDescription';
import RTCView, { type RTCVideoViewProps, type RTCIOSPIPOptions } from './RTCView';
import ScreenCapturePickerView from './ScreenCapturePickerView';

Logger.enable(`${Logger.ROOT_PREFIX}:*`);

// Add listeners for the native events early, since they are added asynchronously.
setupNativeEvents();

export {
    RTCIceCandidate,
    RTCPeerConnection,
    RTCSessionDescription,
    RTCView,
    RTCPIPView,
    ScreenCapturePickerView,
    RTCRtpEncodingParameters,
    RTCRtpTransceiver,
    RTCRtpReceiver,
    RTCRtpSender,
    RTCRtpSendParameters,
    RTCErrorEvent,
    RTCAudioSession,
    MediaStream,
    MediaStreamTrack,
    type MediaTrackSettings,
    type RTCRtpEncodingParametersInit,
    type RTCRtpSendParametersInit,
    type RTCVideoViewProps,
    type RTCIOSPIPOptions,
    mediaDevices,
    permissions,
    registerGlobals,
    startIOSPIP,
    stopIOSPIP,
};

declare const global: any;

function registerGlobals(): void {
    // Should not happen. React Native has a global navigator object.
    if (typeof global.navigator !== 'object') {
        throw new Error('navigator is not an object');
    }

    if (!global.navigator.mediaDevices) {
        global.navigator.mediaDevices = {};
    }

    global.navigator.mediaDevices.getUserMedia = mediaDevices.getUserMedia.bind(mediaDevices);
    global.navigator.mediaDevices.getDisplayMedia = mediaDevices.getDisplayMedia.bind(mediaDevices);
    global.navigator.mediaDevices.enumerateDevices = mediaDevices.enumerateDevices.bind(mediaDevices);

    global.RTCIceCandidate = RTCIceCandidate;
    global.RTCPeerConnection = RTCPeerConnection;
    global.RTCRtpReceiver = RTCRtpReceiver;
    global.RTCRtpSender = RTCRtpReceiver;
    global.RTCSessionDescription = RTCSessionDescription;
    global.MediaStream = MediaStream;
    global.MediaStreamTrack = MediaStreamTrack;
    global.MediaStreamTrackEvent = MediaStreamTrackEvent;
    global.RTCRtpTransceiver = RTCRtpTransceiver;
    global.RTCRtpReceiver = RTCRtpReceiver;
    global.RTCRtpSender = RTCRtpSender;
    global.RTCErrorEvent = RTCErrorEvent;
}

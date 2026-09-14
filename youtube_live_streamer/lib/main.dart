import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';

const _websiteUrl = 'https://anpesi1-collab.github.io';
const _captureHeight = 400.0;

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  runApp(const YouTubeLiveStreamerApp());
}

class YouTubeLiveStreamerApp extends StatelessWidget {
  const YouTubeLiveStreamerApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'YouTube Live',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF08090C),
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFFFF0033),
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
        inputDecorationTheme: InputDecorationTheme(
          filled: true,
          fillColor: const Color(0xFF15171D),
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(14),
            borderSide: const BorderSide(color: Color(0xFF30343D)),
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(14),
            borderSide: const BorderSide(color: Color(0xFF30343D)),
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(14),
            borderSide: const BorderSide(color: Color(0xFFFF0033), width: 1.5),
          ),
        ),
      ),
      home: const StreamHomePage(),
    );
  }
}

class StreamHomePage extends StatefulWidget {
  const StreamHomePage({super.key});

  @override
  State<StreamHomePage> createState() => _StreamHomePageState();
}

class _StreamHomePageState extends State<StreamHomePage> {
  static const _channel = MethodChannel('youtube_live_streamer/screen_capture');

  final _streamKeyController = TextEditingController();
  bool _isLive = false;
  bool _isStarting = false;
  bool _obscureKey = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _channel.setMethodCallHandler(_handleNativeEvent);
  }

  Future<void> _handleNativeEvent(MethodCall call) async {
    if (!mounted) return;

    if (call.method == 'captureDenied') {
      setState(() {
        _isLive = false;
        _isStarting = false;
        _error = 'Screen capture permission was not granted.';
      });
    } else if (call.method == 'captureStarted') {
      setState(() {
        _isLive = true;
        _isStarting = false;
        _error = null;
      });
    } else if (call.method == 'captureStopped' ||
        call.method == 'streamEnded') {
      setState(() {
        _isLive = false;
        _isStarting = false;
      });
    } else if (call.method == 'streamError') {
      setState(() {
        _isLive = false;
        _isStarting = false;
        _error = call.arguments?.toString() ?? 'The stream stopped unexpectedly.';
      });
    }
  }

  Future<void> _toggleLive() async {
    if (_isStarting) return;

    if (_isLive) {
      await _stopLive();
      return;
    }

    final streamKey = _streamKeyController.text.trim();
    if (streamKey.isEmpty) {
      setState(() => _error = 'Enter your YouTube stream key first.');
      return;
    }

    FocusManager.instance.primaryFocus?.unfocus();
    setState(() {
      _isStarting = true;
      _error = null;
    });

    try {
      final mediaQuery = MediaQuery.of(context);
      final pixelRatio = mediaQuery.devicePixelRatio;
      await _channel.invokeMethod<void>('startCapture', {
        'streamKey': streamKey,
        'captureWidth': (mediaQuery.size.width * pixelRatio).round(),
        'captureHeight': (mediaQuery.size.height * pixelRatio).round(),
        'cropHeight': (_captureHeight * pixelRatio).round(),
      });
    } on PlatformException catch (exception) {
      if (!mounted) return;
      setState(() {
        _isStarting = false;
        _error = exception.message ?? 'Unable to start screen capture.';
      });
    }
  }

  Future<void> _stopLive() async {
    try {
      await _channel.invokeMethod<void>('stopCapture');
    } on PlatformException catch (exception) {
      if (!mounted) return;
      setState(() => _error = exception.message ?? 'Unable to stop the stream.');
    }
  }

  @override
  void dispose() {
    _streamKeyController.dispose();
    _channel.invokeMethod<void>('stopCapture');
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final buttonLabel = _isStarting
        ? 'STARTING…'
        : _isLive
            ? 'STOP LIVE'
            : 'GO LIVE';

    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            SizedBox(
              height: _captureHeight,
              width: double.infinity,
              child: InAppWebView(
                initialUrlRequest: URLRequest(url: WebUri(_websiteUrl)),
                initialSettings: InAppWebViewSettings(
                  javaScriptEnabled: true,
                  mediaPlaybackRequiresUserGesture: false,
                  allowsInlineMediaPlayback: true,
                  transparentBackground: false,
                ),
                onLoadError: (controller, url, code, message) {
                  if (!mounted) return;
                  setState(() => _error = 'Website failed to load: $message');
                },
              ),
            ),
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(20, 24, 20, 24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Text(
                      'Stream setup',
                      style: TextStyle(
                        fontSize: 24,
                        fontWeight: FontWeight.w800,
                        letterSpacing: -0.5,
                      ),
                    ),
                    const SizedBox(height: 7),
                    Text(
                      'Only the website preview above is included in the live stream.',
                      style: TextStyle(
                        color: Colors.white.withOpacity(0.58),
                        fontSize: 14,
                      ),
                    ),
                    const SizedBox(height: 22),
                    TextField(
                      controller: _streamKeyController,
                      enabled: !_isLive && !_isStarting,
                      obscureText: _obscureKey,
                      autocorrect: false,
                      enableSuggestions: false,
                      textInputAction: TextInputAction.done,
                      decoration: InputDecoration(
                        labelText: 'YouTube Stream Key',
                        hintText: 'Paste your stream key',
                        prefixIcon: const Icon(Icons.key_rounded),
                        suffixIcon: IconButton(
                          tooltip: _obscureKey ? 'Show key' : 'Hide key',
                          onPressed: () =>
                              setState(() => _obscureKey = !_obscureKey),
                          icon: Icon(
                            _obscureKey
                                ? Icons.visibility_rounded
                                : Icons.visibility_off_rounded,
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(height: 18),
                    if (_error != null) ...[
                      Container(
                        padding: const EdgeInsets.all(14),
                        decoration: BoxDecoration(
                          color: const Color(0xFF3A1119),
                          borderRadius: BorderRadius.circular(12),
                          border: Border.all(color: const Color(0xFF7D2637)),
                        ),
                        child: Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const Icon(
                              Icons.error_outline_rounded,
                              color: Color(0xFFFF6B82),
                              size: 20,
                            ),
                            const SizedBox(width: 10),
                            Expanded(
                              child: Text(
                                _error!,
                                style: const TextStyle(
                                  color: Color(0xFFFFB2BD),
                                  height: 1.35,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 18),
                    ],
                    SizedBox(
                      height: 58,
                      child: FilledButton(
                        onPressed: _toggleLive,
                        style: FilledButton.styleFrom(
                          backgroundColor: _isLive
                              ? const Color(0xFF2B2E36)
                              : const Color(0xFFFF0033),
                          foregroundColor: Colors.white,
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(16),
                          ),
                        ),
                        child: _isStarting
                            ? const SizedBox(
                                width: 21,
                                height: 21,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2.4,
                                  color: Colors.white,
                                ),
                              )
                            : Text(
                                buttonLabel,
                                style: const TextStyle(
                                  fontWeight: FontWeight.w800,
                                  letterSpacing: 0.6,
                                ),
                              ),
                      ),
                    ),
                    const SizedBox(height: 18),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(
                          _isLive
                              ? Icons.circle
                              : Icons.info_outline_rounded,
                          size: 12,
                          color: _isLive
                              ? const Color(0xFFFF0033)
                              : Colors.white.withOpacity(0.42),
                        ),
                        const SizedBox(width: 7),
                        Text(
                          _isLive
                              ? 'LIVE — streaming the website preview'
                              : 'Ready to stream',
                          style: TextStyle(
                            color: Colors.white.withOpacity(0.54),
                            fontSize: 12,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
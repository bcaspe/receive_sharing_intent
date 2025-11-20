import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../listen_sharing_intent.dart';

class ReceiveSharingIntentMobile extends ReceiveSharingIntent {
  @visibleForTesting
  final mChannel = const MethodChannel('receive_sharing_intent/messages');

  @visibleForTesting
  final eChannelMedia =
      const EventChannel("receive_sharing_intent/events-media");

  static Stream<List<SharedMediaFile>>? _streamMedia;

  @override
Future<List<SharedMediaFile>> getInitialMedia() async {
  print('[listen_sharing_intent] getInitialMedia called');
  final json = await mChannel.invokeMethod('getInitialMedia');
  print('[listen_sharing_intent] getInitialMedia raw response: $json');
  print('[listen_sharing_intent] getInitialMedia response type: ${json.runtimeType}');
  
  if (json == null) {
    print('[listen_sharing_intent] getInitialMedia: json is null, returning empty list');
    return [];
  }

  try {
    final encoded = jsonDecode(json);
    print('[listen_sharing_intent] getInitialMedia decoded: $encoded');
    print('[listen_sharing_intent] getInitialMedia length: ${encoded.length}');
    
    final List<SharedMediaFile> files = [];
    
    for (var i = 0; i < encoded.length; i++) {
      final fileMap = encoded[i];
      print('[listen_sharing_intent] getInitialMedia fileMap[$i]: $fileMap');
      print('[listen_sharing_intent] getInitialMedia fileMap[$i] path: ${fileMap['path']}');
      print('[listen_sharing_intent] getInitialMedia fileMap[$i] type: ${fileMap['type']}');
      
      try {
        if (fileMap['type'] == null || fileMap['type'].toString().isEmpty) {
          print('[listen_sharing_intent] getInitialMedia fileMap[$i] skipped: missing type');
          continue;
        }
        
        final file = SharedMediaFile.fromMap(fileMap);
        print('[listen_sharing_intent] getInitialMedia created file: path=${file.path}, type=${file.type}');
        
        if (file.path != null && file.path!.isNotEmpty) {
          files.add(file);
        } else {
          print('[listen_sharing_intent] getInitialMedia fileMap[$i] skipped: path is null or empty');
        }
      } catch (e, stack) {
        print('[listen_sharing_intent] getInitialMedia ERROR: $e');
        print('[listen_sharing_intent] getInitialMedia Stack: $stack');
        continue;
      }
    }
    
    print('[listen_sharing_intent] getInitialMedia returning ${files.length} files');
    return files;
  } catch (e, stack) {
    print('[listen_sharing_intent] getInitialMedia JSON decode ERROR: $e');
    print('[listen_sharing_intent] getInitialMedia Stack: $stack');
    return [];
  }
}

  @override
Stream<List<SharedMediaFile>> getMediaStream() {
  if (_streamMedia == null) {
    final stream = eChannelMedia.receiveBroadcastStream().cast<String?>();
    _streamMedia = stream.transform<List<SharedMediaFile>>(
      StreamTransformer<String?, List<SharedMediaFile>>.fromHandlers(
        handleData: (data, sink) {
          print('[listen_sharing_intent] getMediaStream received data: $data');
          
          if (data == null) {
            print('[listen_sharing_intent] data is null, returning empty list');
            sink.add(<SharedMediaFile>[]);
            return;
          }
          
          try {
            final encoded = jsonDecode(data);
            print('[listen_sharing_intent] decoded JSON: $encoded');
            print('[listen_sharing_intent] JSON type: ${encoded.runtimeType}');
            print('[listen_sharing_intent] JSON length: ${encoded.length}');
            
            final List<SharedMediaFile> files = [];
            
            for (var i = 0; i < encoded.length; i++) {
              final fileMap = encoded[i];
              print('[listen_sharing_intent] fileMap[$i]: $fileMap');
              print('[listen_sharing_intent] fileMap[$i] keys: ${fileMap.keys.toList()}');
              print('[listen_sharing_intent] fileMap[$i] path: ${fileMap['path']} (type: ${fileMap['path'].runtimeType})');
              print('[listen_sharing_intent] fileMap[$i] type: ${fileMap['type']} (type: ${fileMap['type'].runtimeType})');
              print('[listen_sharing_intent] fileMap[$i] mimeType: ${fileMap['mimeType']}');
              
              try {
                // Skip if type is missing
                if (fileMap['type'] == null || fileMap['type'].toString().isEmpty) {
                  print('[listen_sharing_intent] fileMap[$i] skipped: missing type');
                  continue;
                }
                
                // Try to create the file
                print('[listen_sharing_intent] attempting to create SharedMediaFile from fileMap[$i]');
                final file = SharedMediaFile.fromMap(fileMap);
                print('[listen_sharing_intent] created SharedMediaFile: path=${file.path}, type=${file.type}');
                
                // Only add if path exists
                if (file.path != null && file.path!.isNotEmpty) {
                  files.add(file);
                  print('[listen_sharing_intent] added file to list');
                } else {
                  print('[listen_sharing_intent] fileMap[$i] skipped: path is null or empty');
                }
              } catch (e, stack) {
                print('[listen_sharing_intent] ERROR creating SharedMediaFile from fileMap[$i]: $e');
                print('[listen_sharing_intent] Stack trace: $stack');
                continue;
              }
            }
            
            print('[listen_sharing_intent] returning ${files.length} files');
            sink.add(files);
          } catch (e, stack) {
            print('[listen_sharing_intent] ERROR decoding JSON: $e');
            print('[listen_sharing_intent] Stack trace: $stack');
            sink.add(<SharedMediaFile>[]);
          }
        },
        handleError: (error, stackTrace, sink) {
          print('[listen_sharing_intent] Stream error: $error');
          print('[listen_sharing_intent] Stack trace: $stackTrace');
          sink.add(<SharedMediaFile>[]);
        },
      ),
    );
  }
  return _streamMedia!;
}

  @override
  Future<dynamic> reset() {
    return mChannel.invokeMethod('reset');
  }
}

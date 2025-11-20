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
  final json = await mChannel.invokeMethod('getInitialMedia');
  if (json == null) return [];

  try {
    final encoded = jsonDecode(json);
    final List<SharedMediaFile> files = [];
    
    for (final fileMap in encoded) {
      try {
        // Skip entries without a path
        if (fileMap['path'] == null || 
            fileMap['path'].toString().trim().isEmpty) {
          continue;
        }
        files.add(SharedMediaFile.fromMap(fileMap));
      } catch (e) {
        // Skip entries that fail to parse
        continue;
      }
    }
    
    return files;
  } catch (e) {
    // If entire payload fails, return empty list
    return [];
  }
}

  override
Stream<List<SharedMediaFile>> getMediaStream() {
  if (_streamMedia == null) {
    final stream = eChannelMedia.receiveBroadcastStream().cast<String?>();
    _streamMedia = stream.transform<List<SharedMediaFile>>(
      StreamTransformer<String?, List<SharedMediaFile>>.fromHandlers(
        handleData: (data, sink) {
  if (data == null) {
    sink.add(<SharedMediaFile>[]);
    return;
  }
  
  try {
    final encoded = jsonDecode(data);
    final List<SharedMediaFile> files = [];
    
    for (final fileMap in encoded) {
      try {
        // Skip if type is missing (required field)
        if (fileMap['type'] == null) {
          continue;
        }
        
        // Try to create the file
        final file = SharedMediaFile.fromMap(fileMap);
        
        // Only add if path exists (since you need it for your use case)
        if (file.path != null && file.path!.isNotEmpty) {
          files.add(file);
        }
      } catch (e) {
        // Skip entries that fail to parse
        continue;
      }
    }
    
    sink.add(files);
  } catch (e) {
    sink.add(<SharedMediaFile>[]);
  }
},
        handleError: (error, stackTrace, sink) {
          // Handle stream errors gracefully
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

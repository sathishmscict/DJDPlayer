/* //device/samples/SampleCode/src/com/android/samples/app/RemoteServiceInterface.java
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

package nu.staldal.djdplayer;

interface IMediaPlaybackService {
    void openFile(String path);
    void open(in long [] list, int position);
    int getQueuePosition();
    boolean isPlaying();
    void stop();
    void pause();
    void play();
    void prev();
    void next();
    long duration();
    long position();
    long seek(long pos);
    String getTrackName();
    long getArtistId();
    String getArtistName();
    long getGenreId();
    String getGenreName();
    long getAlbumId();
    String getAlbumName();
    long getNextTrackId();
    String getNextTrackName();
    long getNextArtistId();
    String getNextArtistName();
    long getNextGenreId();
    String getNextGenreName();
    long getNextAlbumId();
    String getNextAlbumName();
    void enqueue(in long [] list, int action);
    long [] getQueue();
    void moveQueueItem(int from, int to);
    void setQueuePosition(int index);
    String getPath();
    long getAudioId();
    void doShuffle();
    int removeTracks(int first, int last);
    int removeTrack(long id);
    void setRepeatMode(int repeatmode);
    int getRepeatMode();
    int getMediaMountedCount();
    int getAudioSessionId();
}
